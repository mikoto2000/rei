package dev.mikoto2000.rei.activity.behavior;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import javax.sql.DataSource;

/**
 * SQLite作業習慣状態の記録を保持し、後続の処理で参照できるようにする。
 *
 * <p>Lazy, small checkpoint and bounded transition history; never writes every NONE assessment.
 */
public final class SqliteBehaviorStateStore implements BehaviorStateStore {
  /** データ取得元。 */
  private final DataSource dataSource;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  /** 初期化済み。 */
  private boolean initialized;

  /** 指定された値や利用先を使って、SqliteBehaviorStateStoreを初期化する。 */
  public SqliteBehaviorStateStore(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** 作業習慣の支援に関する初期化を処理する。 */
  private void initialize() throws Exception {
    if (initialized) return;
    try (var c = dataSource.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "CREATE TABLE IF NOT EXISTS activity_behavior_state (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS activity_behavior_transitions (id INTEGER PRIMARY KEY AUTOINCREMENT, payload TEXT NOT NULL)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS activity_behavior_events (id TEXT PRIMARY KEY, occurred_at INTEGER NOT NULL, payload TEXT NOT NULL)");
      s.execute(
          "CREATE INDEX IF NOT EXISTS activity_behavior_events_time ON activity_behavior_events(occurred_at)");
      initialized = true;
    }
  }

  /** 保存された情報を読み込み、現在の処理で使える形にする。 */
  @Override
  public synchronized BehaviorState load() {
    try {
      initialize();
      try (var c = dataSource.getConnection();
          var s = c.createStatement();
          var r = s.executeQuery("SELECT payload FROM activity_behavior_state WHERE id=1")) {
        return r.next()
            ? mapper.readValue(r.getString(1), BehaviorState.class)
            : BehaviorState.empty();
      }
    } catch (Exception e) {
      throw new IllegalStateException("Behavior state read failed", e);
    }
  }

  /** 後から参照できるよう、指定された情報を保存する。 */
  @Override
  public synchronized void save(BehaviorState state, BehaviorAssessment assessment) {
    try {
      initialize();
      try (var c = dataSource.getConnection()) {
        c.setAutoCommit(false);
        try {
          try (var s =
              c.prepareStatement(
                  "INSERT INTO activity_behavior_state(id,payload) VALUES(1,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload")) {
            s.setString(1, mapper.writeValueAsString(state));
            s.executeUpdate();
          }
          try (var s =
              c.prepareStatement("INSERT INTO activity_behavior_transitions(payload) VALUES(?)")) {
            s.setString(1, mapper.writeValueAsString(assessment));
            s.executeUpdate();
          }
          try (var s = c.createStatement()) {
            s.executeUpdate(
                "DELETE FROM activity_behavior_transitions WHERE id NOT IN (SELECT id FROM activity_behavior_transitions ORDER BY id DESC LIMIT 100)");
          }
          c.commit();
        } catch (Exception e) {
          c.rollback();
          throw e;
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("Behavior state write failed", e);
    }
  }

  /** 既存の記録へ経緯を残すため、イベントを追記する。 */
  @Override
  public synchronized void appendEvent(BehaviorTimelineEvent event) {
    try {
      initialize();
      try (var c = dataSource.getConnection();
          var s =
              c.prepareStatement(
                  "INSERT INTO activity_behavior_events(id,occurred_at,payload) VALUES(?,?,?)")) {
        s.setString(1, event.id());
        s.setLong(2, event.timestamp().toEpochMilli());
        s.setString(3, mapper.writeValueAsString(event));
        s.executeUpdate();
      }
    } catch (Exception e) {
      throw new IllegalStateException("Behavior event write failed", e);
    }
  }

  /** 対象を特定できるよう、イベント一覧期間を探す。 */
  @Override
  public synchronized java.util.List<BehaviorTimelineEvent> findEventsBetween(
      java.time.Instant start, java.time.Instant end) {
    try {
      // A timeline read must not create tables or reinterpret old reservations as deliveries.
      try (var c = dataSource.getConnection()) {
        try (var s = c.createStatement();
            var r =
                s.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='activity_behavior_events'")) {
          if (!r.next()) return java.util.List.of();
        }
        try (var s =
            c.prepareStatement(
                "SELECT payload FROM activity_behavior_events WHERE occurred_at>=? AND occurred_at<? ORDER BY occurred_at,id")) {
          s.setLong(1, start.toEpochMilli());
          s.setLong(2, end.toEpochMilli());
          var events = new java.util.ArrayList<BehaviorTimelineEvent>();
          try (var r = s.executeQuery()) {
            while (r.next())
              events.add(mapper.readValue(r.getString(1), BehaviorTimelineEvent.class));
          }
          return java.util.List.copyOf(events);
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("Behavior event read failed", e);
    }
  }
}
