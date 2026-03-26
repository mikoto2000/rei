package dev.mikoto2000.rei.task;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
    return listOpen(new TaskQuery(null, null, null));
  }

  public List<Task> listOpen(TaskQuery query) {
    StringBuilder sql = new StringBuilder("""
        SELECT id, title, due_date, priority, status, tags, created_at, completed_at
        FROM tasks
        WHERE status = ?
        """);
    List<Object> params = new ArrayList<>();
    params.add(TaskStatus.OPEN.name());

    if (query.priority() != null) {
      sql.append(" AND priority <= ?");
      params.add(query.priority());
    }
    if (query.tag() != null && !query.tag().isBlank()) {
      sql.append(" AND (tags = ? OR tags LIKE ? OR tags LIKE ? OR tags LIKE ?)");
      params.add(query.tag());
      params.add(query.tag() + ",%");
      params.add("%," + query.tag());
      params.add("%," + query.tag() + ",%");
    }
    if (query.dueBefore() != null) {
      sql.append(" AND due_date IS NOT NULL AND due_date <= ?");
      params.add(query.dueBefore().toString());
    }
    sql.append(" ORDER BY priority ASC, due_date ASC, id ASC");

    return jdbcClient.sql(sql.toString())
        .params(params)
        .query(this::mapTask)
        .list();
  }

  public Task complete(long id) {
    OffsetDateTime completedAt = OffsetDateTime.now(ZoneOffset.UTC);

    jdbcClient.sql("""
        UPDATE tasks
        SET status = ?, completed_at = ?
        WHERE id = ?
        """)
        .params(TaskStatus.DONE.name(), completedAt.toString(), id)
        .update();

    return jdbcClient.sql("""
        SELECT id, title, due_date, priority, status, tags, created_at, completed_at
        FROM tasks
        WHERE id = ?
        """)
        .param(id)
        .query(this::mapTask)
        .single();
  }

  public void delete(long id) {
    jdbcClient.sql("DELETE FROM tasks WHERE id = ?")
        .param(id)
        .update();
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
