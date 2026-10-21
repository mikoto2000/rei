package dev.mikoto2000.rei.reminder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** リマインダーのユースケースを実現する。 */
@Service
public class ReminderService {

  /** データベースへの接続クライアント。 */
  private final JdbcClient jdbcClient;

  /** 指定された値や利用先を使って、ReminderServiceを初期化する。 */
  public ReminderService(DataSource dataSource) {
    this.jdbcClient = JdbcClient.create(dataSource);
    initializeSchema(dataSource);
  }

  /** 新たな対象を管理できるよう、日時を追加する。 */
  public Reminder addAt(String message, OffsetDateTime remindAt) {
    return insert(message, ReminderType.AT_TIME, remindAt, null, null);
  }

  /** 新たな対象を管理できるよう、変更前を追加する。 */
  public Reminder addBefore(String message, OffsetDateTime targetAt, int minutesBefore) {
    return insert(
        message,
        ReminderType.BEFORE_TARGET,
        targetAt.minusMinutes(minutesBefore),
        targetAt,
        minutesBefore);
  }

  /** 利用可能な対象を確認できるよう、活動中を一覧にする。 */
  public List<Reminder> listActive() {
    return jdbcClient
        .sql(
            """
        SELECT id, message, type, remind_at, remind_at_epoch, target_at, minutes_before, notified, created_at, notified_at
        FROM reminders
        WHERE notified = 0
        ORDER BY remind_at_epoch ASC, id ASC
        """)
        .query(this::mapReminder)
        .list();
  }

  /** 対象を特定できるよう、期限を探す。 */
  public List<Reminder> findDue(OffsetDateTime now) {
    return jdbcClient
        .sql(
            """
        SELECT id, message, type, remind_at, remind_at_epoch, target_at, minutes_before, notified, created_at, notified_at
        FROM reminders
        WHERE notified = 0 AND remind_at_epoch <= ?
        ORDER BY remind_at_epoch ASC, id ASC
        """)
        .param(toEpochMillis(now))
        .query(this::mapReminder)
        .list();
  }

  /** 後続の判断に現在の状態を反映するため、通知済みの状態を記録する。 */
  public Reminder markNotified(long id, OffsetDateTime notifiedAt) {
    jdbcClient
        .sql(
            """
        UPDATE reminders
        SET notified = 1, notified_at = ?
        WHERE id = ?
        """)
        .params(notifiedAt.toString(), id)
        .update();

    return findById(id);
  }

  /** 指定された対象を削除する。 */
  public void delete(long id) {
    jdbcClient.sql("DELETE FROM reminders WHERE id = ?").param(id).update();
  }

  /** リマインダーの管理で用いる追加を返す。 */
  private Reminder insert(
      String message,
      ReminderType type,
      OffsetDateTime remindAt,
      OffsetDateTime targetAt,
      Integer minutesBefore) {
    OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    Long id =
        jdbcClient
            .sql(
                """
        INSERT INTO reminders (message, type, remind_at, remind_at_epoch, target_at, minutes_before, notified, created_at, notified_at)
        VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)
        RETURNING id
        """)
            .params(
                message,
                type.name(),
                remindAt.toString(),
                toEpochMillis(remindAt),
                targetAt == null ? null : targetAt.toString(),
                minutesBefore,
                createdAt.toString(),
                null)
            .query(Long.class)
            .single();

    return new Reminder(
        id, message, type, remindAt, targetAt, minutesBefore, false, createdAt, null);
  }

  /** 対象を特定できるよう、別識別子を探す。 */
  public Reminder findById(long id) {
    return jdbcClient
        .sql(
            """
        SELECT id, message, type, remind_at, remind_at_epoch, target_at, minutes_before, notified, created_at, notified_at
        FROM reminders
        WHERE id = ?
        """)
        .param(id)
        .query(this::mapReminder)
        .single();
  }

  /** リマインダーの管理で用いる対応表リマインダーを返す。 */
  private Reminder mapReminder(ResultSet rs, int rowNum) throws SQLException {
    String targetAt = rs.getString("target_at");
    String notifiedAt = rs.getString("notified_at");
    Integer minutesBefore = (Integer) rs.getObject("minutes_before");

    return new Reminder(
        rs.getLong("id"),
        rs.getString("message"),
        ReminderType.valueOf(rs.getString("type")),
        OffsetDateTime.parse(rs.getString("remind_at")),
        targetAt == null ? null : OffsetDateTime.parse(targetAt),
        minutesBefore,
        rs.getInt("notified") == 1,
        OffsetDateTime.parse(rs.getString("created_at")),
        notifiedAt == null ? null : OffsetDateTime.parse(notifiedAt));
  }

  /** リマインダーの管理に関する初期化データ形式を処理する。 */
  private void initializeSchema(DataSource dataSource) {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.executeUpdate(
          """
          CREATE TABLE IF NOT EXISTS reminders (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            message TEXT NOT NULL,
            type TEXT NOT NULL,
            remind_at TEXT NOT NULL,
            remind_at_epoch INTEGER,
            target_at TEXT,
            minutes_before INTEGER,
            notified INTEGER NOT NULL,
            created_at TEXT NOT NULL,
            notified_at TEXT
          )
          """);
      ensureEpochColumn(statement);
      backfillEpoch(statement);
    } catch (SQLException e) {
      throw new IllegalStateException("reminders テーブルの初期化に失敗しました", e);
    }
  }

  /** 後続の操作に必要な条件を満たすよう、基準時刻列を利用できる状態に整える。 */
  private void ensureEpochColumn(java.sql.Statement statement) throws SQLException {
    try {
      statement.executeUpdate("ALTER TABLE reminders ADD COLUMN remind_at_epoch INTEGER");
    } catch (SQLException e) {
      if (!e.getMessage().contains("duplicate column name")) {
        throw e;
      }
    }
  }

  /** リマインダーの管理に関するbackfill基準時刻を処理する。 */
  private void backfillEpoch(java.sql.Statement statement) throws SQLException {
    List<ReminderEpochUpdate> updates = new ArrayList<>();
    try (var resultSet =
        statement.executeQuery(
            "SELECT id, remind_at FROM reminders WHERE remind_at_epoch IS NULL")) {
      while (resultSet.next()) {
        updates.add(
            new ReminderEpochUpdate(
                resultSet.getLong("id"),
                toEpochMillis(OffsetDateTime.parse(resultSet.getString("remind_at")))));
      }
    }

    for (ReminderEpochUpdate update : updates) {
      jdbcClient
          .sql("UPDATE reminders SET remind_at_epoch = ? WHERE id = ?")
          .params(update.remindAtEpoch(), update.id())
          .update();
    }
  }

  /** 利用先へ渡せるよう、基準時刻ミリ秒数の形式に変換する。 */
  private long toEpochMillis(OffsetDateTime dateTime) {
    return dateTime.toInstant().toEpochMilli();
  }

  /**
   * リマインダーの管理で受け渡すリマインダー基準時刻更新を表す。
   *
   * @param id 識別子
   * @param remindAtEpoch remind日時基準時刻
   */
  private record ReminderEpochUpdate(long id, long remindAtEpoch) {}
}
