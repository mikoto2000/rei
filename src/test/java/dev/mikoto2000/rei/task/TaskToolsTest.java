package dev.mikoto2000.rei.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TaskToolsTest {

  @Test
  void taskCreateAndTaskListDelegateToService() {
    TaskService service = Mockito.mock(TaskService.class);
    TaskTools tools = new TaskTools(service);

    Task created = task(1L, "資料作成", LocalDate.of(2026, 4, 3), 2, TaskStatus.OPEN, List.of("sales", "document"));
    when(service.add("資料作成", LocalDate.of(2026, 4, 3), 2, List.of("sales", "document"))).thenReturn(created);
    when(service.listOpen()).thenReturn(List.of(created));

    Task actual = tools.taskCreate("資料作成", "2026-04-03", 2, List.of("sales", "document"));
    List<Task> listed = tools.taskList();

    assertSame(created, actual);
    assertEquals(List.of(created), listed);
  }

  @Test
  void taskCreatePrintsErrorAndRethrowsWhenServiceFails() {
    TaskService service = Mockito.mock(TaskService.class);
    TaskTools tools = new TaskTools(service);
    IllegalStateException failure = new IllegalStateException("Google Tasks へのタスク追加に失敗しました",
        new IllegalStateException("Google Task integration is disabled"));
    when(service.add("資料作成", null, 3, List.of())).thenThrow(failure);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      IllegalStateException actual = assertThrows(IllegalStateException.class,
          () -> tools.taskCreate("資料作成", null, 3, null));
      assertSame(failure, actual);
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("[error] Google Tasks へのタスク追加に失敗しました: Google Task integration is disabled"));
  }

  @Test
  void taskCompleteDelegatesToService() {
    TaskService service = Mockito.mock(TaskService.class);
    TaskTools tools = new TaskTools(service);
    Task done = task(2L, "会議準備", null, 3, TaskStatus.DONE, List.of());
    when(service.complete(2L)).thenReturn(done);

    Task actual = tools.taskComplete(2L);

    assertSame(done, actual);
    verify(service).complete(2L);
  }

  @Test
  void taskUpdateDelegatesToService() {
    TaskService service = Mockito.mock(TaskService.class);
    TaskTools tools = new TaskTools(service);
    Task updated = task(3L, "要件更新", LocalDate.of(2026, 4, 10), 1, TaskStatus.OPEN, List.of("urgent"));
    when(service.update(3L, "要件更新", LocalDate.of(2026, 4, 10), 1, List.of("urgent"))).thenReturn(updated);

    Task actual = tools.taskUpdate(3L, "要件更新", "2026-04-10", 1, List.of("urgent"));

    assertSame(updated, actual);
    verify(service).update(3L, "要件更新", LocalDate.of(2026, 4, 10), 1, List.of("urgent"));
  }

  @Test
  void taskUpdateDeadlineDelegatesToService() {
    TaskService service = Mockito.mock(TaskService.class);
    TaskTools tools = new TaskTools(service);
    Task updated = task(4L, "期限変更", LocalDate.of(2026, 4, 10), 2, TaskStatus.OPEN, List.of());
    when(service.updateDeadline(4L, LocalDate.of(2026, 4, 10))).thenReturn(updated);

    Task actual = tools.taskUpdateDeadline(4L, "2026-04-10");

    assertSame(updated, actual);
    verify(service).updateDeadline(4L, LocalDate.of(2026, 4, 10));
  }

  @Test
  void taskDeleteDelegatesToService() {
    TaskService service = Mockito.mock(TaskService.class);
    TaskTools tools = new TaskTools(service);

    tools.taskDelete(5L);

    verify(service).delete(5L);
  }

  @Test
  void taskCreateUsesEmptyTagListWhenNull() {
    TaskService service = Mockito.mock(TaskService.class);
    TaskTools tools = new TaskTools(service);
    Task created = task(6L, "タグなし", null, 3, TaskStatus.OPEN, List.of());
    when(service.add("タグなし", null, 3, List.of())).thenReturn(created);

    Task actual = tools.taskCreate("タグなし", null, 3, null);

    assertSame(created, actual);
    verify(service).add("タグなし", null, 3, List.of());
  }

  @Test
  void taskUpdatePassesNullValuesAsIs() {
    TaskService service = Mockito.mock(TaskService.class);
    TaskTools tools = new TaskTools(service);
    Task updated = task(7L, "現状維持", LocalDate.of(2026, 4, 3), 2, TaskStatus.OPEN, List.of("team"));
    when(service.update(7L, null, null, 1, null)).thenReturn(updated);

    Task actual = tools.taskUpdate(7L, null, null, 1, null);

    assertSame(updated, actual);
    verify(service).update(7L, null, null, 1, null);
  }

  private Task task(long id, String title, LocalDate dueDate, int priority, TaskStatus status, List<String> tags) {
    return new Task(id, title, dueDate, priority, status, tags, OffsetDateTime.now(ZoneOffset.UTC), null);
  }
}