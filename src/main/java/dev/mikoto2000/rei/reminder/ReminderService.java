package dev.mikoto2000.rei.reminder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ReminderService {

  private final JdbcClient jdbcClient;

  public ReminderService(DataSource dataSource) {
    this.jdbcClient = JdbcClient.create(dataSource);
    initializeSchema(dataSource);
  }

  public Reminder addAt(String message, OffsetDateTime remindAt) {
    return insert(message, ReminderType.AT_TIME, remindAt, null, null);
  }

  public Reminder addBefore(String message, OffsetDateTime targetAt, int minutesBefore) {
    return insert(message, ReminderType.BEFORE_TARGET, targetAt.minusMinutes(minutesBefore), targetAt, minutesBefore);
  }

  public List<Reminder> listActive() {
    return jdbcClient.sql("""
        SELECT id, message, type, remind_at, target_at, minutes_before, notified, created_at, notified_at
        FROM reminders
        WHERE notified = 0
        ORDER BY remind_at ASC, id ASC
        """)
        .query(this::mapReminder)
        .list();
  }

  public List<Reminder> findDue(OffsetDateTime now) {
    return jdbcClient.sql("""
        SELECT id, message, type, remind_at, target_at, minutes_before, notified, created_at, notified_at
        FROM reminders
        WHERE notified = 0 AND remind_at <= ?
        ORDER BY remind_at ASC, id ASC
        """)
        .param(now.toString())
        .query(this::mapReminder)
        .list();
  }

  public Reminder markNotified(long id, OffsetDateTime notifiedAt) {
    jdbcClient.sql("""
        UPDATE reminders
        SET notified = 1, notified_at = ?
        WHERE id = ?
        """)
        .params(notifiedAt.toString(), id)
        .update();

    return findById(id);
  }

  public void delete(long id) {
    jdbcClient.sql("DELETE FROM reminders WHERE id = ?")
        .param(id)
        .update();
  }

  private Reminder insert(String message, ReminderType type, OffsetDateTime remindAt, OffsetDateTime targetAt, Integer minutesBefore) {
    OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    Long id = jdbcClient.sql("""
        INSERT INTO reminders (message, type, remind_at, target_at, minutes_before, notified, created_at, notified_at)
        VALUES (?, ?, ?, ?, ?, 0, ?, ?)
        RETURNING id
        """)
        .params(
            message,
            type.name(),
            remindAt.toString(),
            targetAt == null ? null : targetAt.toString(),
            minutesBefore,
            createdAt.toString(),
            null)
        .query(Long.class)
        .single();

    return new Reminder(id, message, type, remindAt, targetAt, minutesBefore, false, createdAt, null);
  }

  private Reminder findById(long id) {
    return jdbcClient.sql("""
        SELECT id, message, type, remind_at, target_at, minutes_before, notified, created_at, notified_at
        FROM reminders
        WHERE id = ?
        """)
        .param(id)
        .query(this::mapReminder)
        .single();
  }

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

  private void initializeSchema(DataSource dataSource) {
    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS reminders (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            message TEXT NOT NULL,
            type TEXT NOT NULL,
            remind_at TEXT NOT NULL,
            target_at TEXT,
            minutes_before INTEGER,
            notified INTEGER NOT NULL,
            created_at TEXT NOT NULL,
            notified_at TEXT
          )
          """);
    } catch (SQLException e) {
      throw new IllegalStateException("reminders テーブルの初期化に失敗しました", e);
    }
  }
}
