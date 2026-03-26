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
    TaskService service = new TaskService(
        new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("task.db")));

    Task created = service.add("設計レビュー", LocalDate.of(2026, 3, 31), 2, List.of("backend", "review"));

    List<Task> tasks = service.listOpen();

    assertEquals(1, tasks.size());
    assertEquals(created, tasks.getFirst());
    assertEquals(TaskStatus.OPEN, tasks.getFirst().status());
    assertEquals(LocalDate.of(2026, 3, 31), tasks.getFirst().dueDate());
    assertEquals(List.of("backend", "review"), tasks.getFirst().tags());
    assertNull(tasks.getFirst().completedAt());
  }
}
