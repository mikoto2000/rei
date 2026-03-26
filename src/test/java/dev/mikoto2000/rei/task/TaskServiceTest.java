package dev.mikoto2000.rei.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TaskServiceTest {

  @TempDir
  Path tempDir;

  @Test
  void addAndListOpenTasks() {
    TaskService service = newService();

    Task created = service.add("設計レビュー", LocalDate.of(2026, 3, 31), 2, List.of("backend", "review"));

    List<Task> tasks = service.listOpen();

    assertEquals(1, tasks.size());
    assertEquals(created, tasks.getFirst());
    assertEquals(TaskStatus.OPEN, tasks.getFirst().status());
    assertEquals(LocalDate.of(2026, 3, 31), tasks.getFirst().dueDate());
    assertEquals(List.of("backend", "review"), tasks.getFirst().tags());
    assertNull(tasks.getFirst().completedAt());
  }

  @Test
  void completeTaskMovesItOutOfOpenList() {
    TaskService service = newService();

    Task created = service.add("議事録作成", LocalDate.of(2026, 4, 1), 1, List.of("meeting"));

    Task completed = service.complete(created.id());

    assertEquals(TaskStatus.DONE, completed.status());
    assertEquals(0, service.listOpen().size());
  }

  @Test
  void deleteRemovesTask() {
    TaskService service = newService();

    Task created = service.add("メール返信", null, 3, List.of());
    service.delete(created.id());

    assertEquals(0, service.listOpen().size());
  }

  @Test
  void listOpenFiltersByPriorityTagAndDueDate() {
    TaskService service = newService();
    service.add("設計レビュー", LocalDate.of(2026, 3, 31), 2, List.of("backend", "review"));
    service.add("営業資料", LocalDate.of(2026, 4, 3), 3, List.of("sales"));
    service.add("バグ修正", LocalDate.of(2026, 3, 28), 1, List.of("backend", "urgent"));

    List<Task> filtered = service.listOpen(new TaskQuery(2, "backend", LocalDate.of(2026, 3, 31)));

    assertEquals(2, filtered.size());
    assertEquals("バグ修正", filtered.get(0).title());
    assertEquals("設計レビュー", filtered.get(1).title());
  }

  private TaskService newService() {
    return new TaskService(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("task.db")));
  }
}
