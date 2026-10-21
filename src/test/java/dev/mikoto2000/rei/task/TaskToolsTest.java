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

/** タスクツール一覧の結果と状態の契約を検証するテスト。 */
class TaskToolsTest {

  /** タスクの管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void taskCreateAndTaskListDelegateToService() {
    TaskService service = Mockito.mock(TaskService.class);
    TaskTools tools = new TaskTools(service);

    Task created = task(1L, "資料作成", LocalDate.of(2026, 4, 3), 2, TaskStatus.OPEN);
    when(service.add("資料作成", LocalDate.of(2026, 4, 3), 2)).thenReturn(created);
    when(service.listOpen()).thenReturn(List.of(created));

    Task actual = tools.taskCreate("資料作成", "2026-04-03", 2);
    List<Task> listed = tools.taskList();

    assertSame(created, actual);
    assertEquals(List.of(created), listed);
  }

  /** タスクの管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void taskCreatePrintsErrorAndRethrowsWhenServiceFails() {
    TaskService service = Mockito.mock(TaskService.class);
    TaskTools tools = new TaskTools(service);
    IllegalStateException failure =
        new IllegalStateException(
            "Google Tasks へのタスク追加に失敗しました",
            new IllegalStateException("Google Task integration is disabled"));
    when(service.add("資料作成", null, 3)).thenThrow(failure);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      IllegalStateException actual =
          assertThrows(IllegalStateException.class, () -> tools.taskCreate("資料作成", null, 3));
      assertSame(failure, actual);
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(
        out.toString()
            .contains("[error] Google Tasks へのタスク追加に失敗しました: Google Task integration is disabled"));
  }

  /** タスクの管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void taskCompleteDelegatesToService() {
    TaskService service = Mockito.mock(TaskService.class);
    TaskTools tools = new TaskTools(service);
    Task done = task(2L, "会議準備", null, 3, TaskStatus.DONE);
    when(service.complete(2L)).thenReturn(done);

    Task actual = tools.taskComplete(2L);

    assertSame(done, actual);
    verify(service).complete(2L);
  }

  /** タスクの管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void taskUpdateDelegatesToService() {
    TaskService service = Mockito.mock(TaskService.class);
    TaskTools tools = new TaskTools(service);
    Task updated = task(3L, "要件更新", LocalDate.of(2026, 4, 10), 1, TaskStatus.OPEN);
    when(service.update(3L, "要件更新", LocalDate.of(2026, 4, 10), 1)).thenReturn(updated);

    Task actual = tools.taskUpdate(3L, "要件更新", "2026-04-10", 1);

    assertSame(updated, actual);
    verify(service).update(3L, "要件更新", LocalDate.of(2026, 4, 10), 1);
  }

  /** タスクの管理で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void taskUpdateDeadlineDelegatesToService() {
    TaskService service = Mockito.mock(TaskService.class);
    TaskTools tools = new TaskTools(service);
    Task updated = task(4L, "期限変更", LocalDate.of(2026, 4, 10), 2, TaskStatus.OPEN);
    when(service.updateDeadline(4L, LocalDate.of(2026, 4, 10))).thenReturn(updated);

    Task actual = tools.taskUpdateDeadline(4L, "2026-04-10");

    assertSame(updated, actual);
    verify(service).updateDeadline(4L, LocalDate.of(2026, 4, 10));
  }

  /** タスクの管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void taskDeleteDelegatesToService() {
    TaskService service = Mockito.mock(TaskService.class);
    TaskTools tools = new TaskTools(service);

    tools.taskDelete(5L);

    verify(service).delete(5L);
  }

  /** タスクについて、受け付ける未指定期限日付を作成することを確認する。 */
  @Test
  void taskCreateAcceptsMissingDueDate() {
    TaskService service = Mockito.mock(TaskService.class);
    TaskTools tools = new TaskTools(service);
    Task created = task(6L, "no deadline", null, 3, TaskStatus.OPEN);
    when(service.add("no deadline", null, 3)).thenReturn(created);

    Task actual = tools.taskCreate("no deadline", null, 3);

    assertSame(created, actual);
    verify(service).add("no deadline", null, 3);
  }

  /** タスクの管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void taskUpdatePassesNullValuesAsIs() {
    TaskService service = Mockito.mock(TaskService.class);
    TaskTools tools = new TaskTools(service);
    Task updated = task(7L, "現状維持", LocalDate.of(2026, 4, 3), 2, TaskStatus.OPEN);
    when(service.update(7L, null, null, 1)).thenReturn(updated);

    Task actual = tools.taskUpdate(7L, null, null, 1);

    assertSame(updated, actual);
    verify(service).update(7L, null, null, 1);
  }

  /** {@code task} の条件を再現するための補助情報を返す。 */
  private Task task(long id, String title, LocalDate dueDate, int priority, TaskStatus status) {
    return new Task(id, title, dueDate, priority, status, OffsetDateTime.now(ZoneOffset.UTC), null);
  }

  /** タスクの管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void actualTaskCompletionPublishesOwnedLifecycleForReflection() {
    var service = Mockito.mock(TaskService.class);
    var tools = new TaskTools(service);
    var events = new java.util.ArrayList<dev.mikoto2000.rei.event.AgentEvent>();
    tools.setEvents(
        events::add,
        new dev.mikoto2000.rei.event.AgentEventFactory(
            java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC)));
    var completed = task(7L, "expected", null, 3, TaskStatus.DONE);
    when(service.complete(7L)).thenReturn(completed);
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "run", "session", java.nio.file.Path.of("."), "project");
    try (var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(owner)) {
      assertSame(completed, tools.taskComplete(7L));
    }
    assertEquals(
        List.of(
            dev.mikoto2000.rei.event.AgentEventType.TASK_STARTED,
            dev.mikoto2000.rei.event.AgentEventType.TASK_COMPLETED),
        events.stream().map(dev.mikoto2000.rei.event.AgentEvent::type).toList());
    assertTrue(
        events.stream()
            .allMatch(e -> "project".equals(e.projectId()) && "session".equals(e.sessionId())));
    when(service.complete(8L)).thenThrow(new IllegalStateException("token=private"));
    try (var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(owner)) {
      assertThrows(IllegalStateException.class, () -> tools.taskComplete(8L));
    }
    var failed = (dev.mikoto2000.rei.event.TaskFailedPayload) events.getLast().payload();
    assertEquals("8", failed.taskId());
    assertTrue(!failed.error().message().contains("private"));
  }

  /** タスクの管理で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  @org.junit.jupiter.api.Tag("integration")
  void taskToolResultReachesPersistentReflection(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
    var source =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:sqlite:" + dir.resolve("reflection.db"));
    var clock = java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC);
    var repo = new dev.mikoto2000.rei.reflection.RunReflectionRepository(source, clock);
    var bus = new dev.mikoto2000.rei.event.InMemoryAgentEventBus();
    var observer = new dev.mikoto2000.rei.reflection.RunReflectionService(repo, bus);
    observer.start();
    try {
      var service = Mockito.mock(TaskService.class);
      var tools = new TaskTools(service);
      tools.setEvents(bus, new dev.mikoto2000.rei.event.AgentEventFactory(clock));
      var done = task(9L, "expected", null, 3, TaskStatus.DONE);
      when(service.complete(9L)).thenReturn(done);
      var owner =
          new dev.mikoto2000.rei.core.chat.AgentRunContext("run", "session", dir, "project");
      try (var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(owner)) {
        tools.taskComplete(9L);
      }
      var item = repo.list("project").getFirst();
      assertEquals("TASK", item.sourceKind());
      assertEquals("9", item.sourceId());
      assertEquals("TASK_COMPLETION_REPORTED", item.gap());
      when(service.complete(10L)).thenReturn(task(10L, "unfinished", null, 3, TaskStatus.OPEN));
      try (var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(owner)) {
        assertThrows(IllegalStateException.class, () -> tools.taskComplete(10L));
      }
      assertEquals(
          "FAILED",
          repo.list("project").stream()
              .filter(i -> i.sourceId().equals("10"))
              .findFirst()
              .orElseThrow()
              .status());
    } finally {
      observer.close();
    }
  }
}
