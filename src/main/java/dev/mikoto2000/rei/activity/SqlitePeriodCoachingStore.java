package dev.mikoto2000.rei.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.time.Instant;
import javax.sql.DataSource;

/**
 * SQLite期間助言の記録を保持し、後続の処理で参照できるようにする。
 *
 * <p>Activity journal-wide preferences; no Project ownership is inferred from screen candidates.
 */
public final class SqlitePeriodCoachingStore implements PeriodCoachingStore {
  /** 取得元。 */
  private final DataSource source;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper mapper = new ObjectMapper();

  /** 初期化済み。 */
  private boolean initialized;

  /** 指定された値や利用先を使って、SqlitePeriodCoachingStoreを初期化する。 */
  public SqlitePeriodCoachingStore(DataSource source) {
    this.source = source;
  }

  /** 作業状況の把握に関する初期化を処理する。 */
  private synchronized void initialize() throws Exception {
    if (initialized) return;
    try (var c = source.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "CREATE TABLE IF NOT EXISTS activity_period_coaching_settings (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL, payload TEXT NOT NULL)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS activity_period_coaching_receipts (period_key TEXT PRIMARY KEY, reason TEXT NOT NULL, shown_at INTEGER NOT NULL, settings_revision INTEGER NOT NULL)");
      s.execute(
          "CREATE INDEX IF NOT EXISTS activity_period_coaching_receipts_time ON activity_period_coaching_receipts(shown_at)");
      try (var insert =
          c.prepareStatement(
              "INSERT OR IGNORE INTO activity_period_coaching_settings VALUES(1,0,?)")) {
        insert.setString(1, mapper.writeValueAsString(PeriodCoaching.Settings.defaults()));
        insert.executeUpdate();
      }
      initialized = true;
    }
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  private Snapshot read(Connection c) throws Exception {
    try (var s = c.createStatement();
        var r =
            s.executeQuery(
                "SELECT revision,payload FROM activity_period_coaching_settings WHERE id=1")) {
      if (!r.next()) throw new IllegalStateException("Missing coaching settings");
      return new Snapshot(
          r.getLong(1), mapper.readValue(r.getString(2), PeriodCoaching.Settings.class));
    }
  }

  /** 保存された情報を読み込み、現在の処理で使える形にする。 */
  @Override
  public Snapshot load() {
    try {
      initialize();
      try (var c = source.getConnection()) {
        return read(c);
      }
    } catch (Exception e) {
      throw failure(e);
    }
  }

  /** 作業状況の把握に必要な操作を扱う。 */
  private interface Operation<T> {
    /** 指定された内容を、現在の対象へ反映する。 */
    T apply(Connection c) throws Exception;
  }

  /** 作業状況の把握で用いる一連の更新を返す。 */
  private <T> T transaction(Operation<T> operation) {
    try {
      initialize();
      try (var c = source.getConnection();
          var s = c.createStatement()) {
        s.execute("PRAGMA busy_timeout=5000");
        s.execute("BEGIN IMMEDIATE");
        try {
          var value = operation.apply(c);
          s.execute("COMMIT");
          return value;
        } catch (Exception e) {
          s.execute("ROLLBACK");
          throw e;
        }
      }
    } catch (Exception e) {
      throw failure(e);
    }
  }

  /** 指定された内容を書き込み先へ反映する。 */
  private Snapshot write(Connection c, PeriodCoaching.Settings settings) throws Exception {
    if (read(c).revision() == Long.MAX_VALUE)
      throw new java.util.ConcurrentModificationException("Coaching revision exhausted");
    try (var s =
        c.prepareStatement(
            "UPDATE activity_period_coaching_settings SET revision=revision+1,payload=? WHERE id=1")) {
      s.setString(1, mapper.writeValueAsString(settings));
      s.executeUpdate();
    }
    return read(c);
  }

  /** 作業状況の把握で用いる設定を返す。 */
  @Override
  public Snapshot configure(PeriodCoaching.Settings settings) {
    return transaction(c -> write(c, settings));
  }

  /** 作業状況の把握で使用する有効状態を設定する。 */
  @Override
  public Snapshot setEnabled(boolean enabled) {
    return transaction(c -> write(c, read(c).settings().withEnabled(enabled)));
  }

  /** 作業状況の把握で用いる期待する内容を返す。 */
  private Snapshot expected(Connection c, long revision) throws Exception {
    var snapshot = read(c);
    if (revision < 0 || snapshot.revision() != revision || revision == Long.MAX_VALUE)
      throw new java.util.ConcurrentModificationException("Coaching settings changed");
    return snapshot;
  }

  /** 作業状況の把握で用いる設定期待する内容を返す。 */
  @Override
  public Snapshot configureExpected(PeriodCoaching.Settings settings, long revision) {
    return transaction(
        c -> {
          expected(c, revision);
          return write(c, settings);
        });
  }

  /** 作業状況の把握で使用する有効状態期待する内容を設定する。 */
  @Override
  public Snapshot setEnabledExpected(boolean enabled, long revision) {
    return transaction(c -> write(c, expected(c, revision).settings().withEnabled(enabled)));
  }

  /** 競合する処理と利用範囲を調整するため、SQLite期間助言保存先を確保する。 */
  @Override
  public String reserve(Snapshot expected, String key, String reason, Instant now) {
    if (key == null
        || key.isBlank()
        || key.length() > 200
        || !java.util.Set.of("BELOW_TARGET", "BELOW_TARGET_DECLINING").contains(reason))
      throw new IllegalArgumentException("Invalid coaching reservation");
    return transaction(
        c -> {
          var current = read(c);
          if (!current.equals(expected)) return "SETTINGS_CHANGED";
          if (!current.settings().enabled()) return "DISABLED";
          try (var s =
              c.prepareStatement(
                  "SELECT 1 FROM activity_period_coaching_receipts WHERE period_key=?")) {
            s.setString(1, key);
            try (var r = s.executeQuery()) {
              if (r.next()) return "ALREADY_SHOWN";
            }
          }
          try (var s = c.createStatement();
              var r =
                  s.executeQuery("SELECT MAX(shown_at) FROM activity_period_coaching_receipts")) {
            if (r.next()) {
              long last = r.getLong(1);
              if (!r.wasNull()
                  && now.getEpochSecond() < last + current.settings().cooldownDays() * 86400L)
                return "COOLDOWN";
            }
          }
          try (var s =
              c.prepareStatement("INSERT INTO activity_period_coaching_receipts VALUES(?,?,?,?)")) {
            s.setString(1, key);
            s.setString(2, reason);
            s.setLong(3, now.getEpochSecond());
            s.setLong(4, current.revision());
            s.executeUpdate();
          }
          return "RESERVED";
        });
  }

  /** 処理を継続できなかった理由を、失敗情報として表す。 */
  private static RuntimeException failure(Exception e) {
    return e instanceof java.util.ConcurrentModificationException conflict
        ? conflict
        : new IllegalStateException("Coaching persistence unavailable", e);
  }
}
