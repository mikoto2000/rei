package dev.mikoto2000.rei.task;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class TaskService {

  private final JdbcClient jdbcClient;

  public TaskService(DataSource dataSource) {
    this.jdbcClient = JdbcClient.create(dataSource);
    initializeSchema(dataSource);
  }

  public Task add(String title, LocalDate dueDate, int priority, List<String> tags) {
    OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    String dueDateValue = dueDate == null ? null : dueDate.toString();
    String tagsValue = String.join(",", tags);

    Long id = jdbcClient.sql("""
        INSERT INTO tasks (title, due_date, priority, status, tags, created_at, completed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        RETURNING id
        """)
        .params(title, dueDateValue, priority, TaskStatus.OPEN.name(), tagsValue, createdAt.toString(), null)
        .query(Long.class)
        .single();

    return new Task(id, title, dueDate, priority, TaskStatus.OPEN, tags, createdAt, null);
  }

  public List<Task> listOpen() {
    return jdbcClient.sql("""
        SELECT id, title, due_date, priority, status, tags, created_at, completed_at
        FROM tasks
        WHERE status = ?
        ORDER BY priority ASC, due_date ASC, id ASC
        """)
        .param(TaskStatus.OPEN.name())
        .query(this::mapTask)
        .list();
  }

  private Task mapTask(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    String dueDate = rs.getString("due_date");
    String tags = rs.getString("tags");
    String completedAt = rs.getString("completed_at");

    return new Task(
        rs.getLong("id"),
        rs.getString("title"),
        dueDate == null ? null : LocalDate.parse(dueDate),
        rs.getInt("priority"),
        TaskStatus.valueOf(rs.getString("status")),
        tags == null || tags.isBlank() ? List.of() : List.of(tags.split(",")),
        OffsetDateTime.parse(rs.getString("created_at")),
        completedAt == null ? null : OffsetDateTime.parse(completedAt));
  }

  private void initializeSchema(DataSource dataSource) {
    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS tasks (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL,
            due_date TEXT,
            priority INTEGER NOT NULL,
            status TEXT NOT NULL,
            tags TEXT NOT NULL,
            created_at TEXT NOT NULL,
            completed_at TEXT
          )
          """);
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("tasks テーブルの初期化に失敗しました", e);
    }
  }
}
