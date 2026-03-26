package dev.mikoto2000.rei.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TaskToolsTest {

  @TempDir
  Path tempDir;

  @Test
  void taskCreateAndTaskListExposeOpenTasks() {
    TaskService service = new TaskService(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("task-tools.db")));
    TaskTools tools = new TaskTools(service);

    Task created = tools.taskCreate("提案書作成", "2026-04-03", 2, List.of("sales", "document"));
    List<Task> tasks = tools.taskList();

    assertEquals(created, tasks.getFirst());
    assertEquals(LocalDate.of(2026, 4, 3), tasks.getFirst().dueDate());
  }

  @Test
  void taskCompleteMarksTaskAsDone() {
    TaskService service = new TaskService(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("task-tools-done.db")));
    TaskTools tools = new TaskTools(service);
    Task created = tools.taskCreate("顧客返信", null, 3, List.of());

    Task completed = tools.taskComplete(created.id());

    assertEquals(TaskStatus.DONE, completed.status());
    assertEquals(0, tools.taskList().size());
  }

  @Test
  void taskUpdateDeadlineChangesDueDate() {
    TaskService service = new TaskService(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("task-tools-deadline.db")));
    TaskTools tools = new TaskTools(service);
    Task created = tools.taskCreate("見積作成", "2026-04-03", 2, List.of("sales"));

    Task updated = tools.taskUpdateDeadline(created.id(), "2026-04-10");

    assertEquals(LocalDate.of(2026, 4, 10), updated.dueDate());
    assertEquals(LocalDate.of(2026, 4, 10), tools.taskList().getFirst().dueDate());
  }

  @Test
  void taskUpdateDeadlineCanClearDueDate() {
    TaskService service = new TaskService(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("task-tools-clear.db")));
    TaskTools tools = new TaskTools(service);
    Task created = tools.taskCreate("社内共有", "2026-04-03", 2, List.of());

    Task updated = tools.taskUpdateDeadline(created.id(), null);

    assertNull(updated.dueDate());
  }
}
