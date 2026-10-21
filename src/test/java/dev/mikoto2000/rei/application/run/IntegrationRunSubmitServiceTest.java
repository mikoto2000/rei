package dev.mikoto2000.rei.application.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarService;
import dev.mikoto2000.rei.task.Task;
import dev.mikoto2000.rei.task.TaskService;
import dev.mikoto2000.rei.task.TaskStatus;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 外部連携実行受付サービスの結果と状態の契約を検証するテスト。 */
class IntegrationRunSubmitServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** クライアント所有権を保持することを確認する。 */
  @Test
  void implicitOAuthOperationsAreQueuedAndRetainClientOwnership() throws Exception {
    var projects = new ProjectRegistry(directory.resolve("projects.json"));
    var project = projects.resolve(directory);
    var lifecycle = mock(SessionLifecycle.class);
    var registry = new RunRegistry(Clock.systemUTC());
    var bus = new InMemoryAgentEventBus();
    var events = new AgentEventFactory(Clock.systemUTC());
    var cancellation = new CommandCancellationService();
    var pending = new ArrayDeque<Runnable>();
    var router =
        new ConversationInputRouter(
            pending::add,
            (context, prompt, queue) -> {
              throw new AssertionError();
            });
    var calendar = mock(GoogleCalendarService.class);
    var tasks = mock(TaskService.class);
    when(tasks.listOpen(any()))
        .thenAnswer(
            call -> {
              assertEquals(project.id(), AgentRunScope.current().projectId());
              assertEquals("session", AgentRunScope.current().conversationId());
              return java.util.List.of();
            });
    try (var runs = new RunService(registry, bus, events, cancellation, router::cancelQueued)) {
      var service =
          new IntegrationRunSubmitService(
              projects,
              lifecycle,
              registry,
              runs,
              router,
              cancellation,
              events,
              bus,
              calendar,
              tasks);
      var operation = service.listGoogleTasks(project.id(), "session", null, null);
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(tasks);
      verify(lifecycle).validate("session", project.id());
      pending.remove().run();
      assertEquals(RunStatus.COMPLETED, runs.get(operation.runId()).status());
      verify(tasks).listOpen(any());
      assertNull(AgentRunScope.current());
    }
  }

  /** 項目一覧を保持することを確認する。 */
  @Test
  void taglessTaskCreationRetainsFieldsAndRunsOnlyInsideCapturedOwnership() throws Exception {
    var projects = new ProjectRegistry(directory.resolve("projects.json"));
    var project = projects.resolve(directory);
    var lifecycle = mock(SessionLifecycle.class);
    var registry = new RunRegistry(Clock.systemUTC());
    var bus = new InMemoryAgentEventBus();
    var events = new AgentEventFactory(Clock.systemUTC());
    var cancellation = new CommandCancellationService();
    var pending = new ArrayDeque<Runnable>();
    var router =
        new ConversationInputRouter(
            pending::add,
            (context, prompt, queue) -> {
              throw new AssertionError("Task creation must not execute chat");
            });
    var calendar = mock(GoogleCalendarService.class);
    var tasks = mock(TaskService.class);
    var due = java.time.LocalDate.of(2026, 10, 20);
    var task = new Task(7, "Review", due, 2, TaskStatus.OPEN, java.time.OffsetDateTime.now(), null);
    when(tasks.add("Review", due, 2))
        .thenAnswer(
            call -> {
              assertEquals(project.id(), AgentRunScope.current().projectId());
              assertEquals("session", AgentRunScope.current().conversationId());
              return task;
            });
    try (var runs = new RunService(registry, bus, events, cancellation, router::cancelQueued)) {
      var service =
          new IntegrationRunSubmitService(
              projects,
              lifecycle,
              registry,
              runs,
              router,
              cancellation,
              events,
              bus,
              calendar,
              tasks);
      var operation = service.addGoogleTask(project.id(), "session", "Review", due, 2);
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(tasks, calendar);
      verify(lifecycle).validate("session", project.id());
      pending.remove().run();
      assertEquals(RunStatus.COMPLETED, runs.get(operation.runId()).status());
      verify(tasks).add("Review", due, 2);
      verifyNoInteractions(calendar);
      assertNull(AgentRunScope.current());
    }
  }

  /** 実行の受付と状態管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void authorizationBeginsOnlyInsideTheCapturedWebRun() throws Exception {
    var projects = new ProjectRegistry(directory.resolve("projects.json"));
    var project = projects.resolve(directory);
    var lifecycle = mock(SessionLifecycle.class);
    var registry = new RunRegistry(Clock.systemUTC());
    var bus = new InMemoryAgentEventBus();
    var events = new AgentEventFactory(Clock.systemUTC());
    var cancellation = new CommandCancellationService();
    var pending = new ArrayDeque<Runnable>();
    var router =
        new ConversationInputRouter(
            pending::add,
            (context, prompt, queue) -> {
              throw new AssertionError("OAuth must not execute chat");
            });
    var context =
        new AgentRunContext(
            "run", "session", directory, project.id(), AgentRunContext.RequestSource.WEB);
    when(lifecycle.submit(
            eq(project),
            eq("session"),
            eq("google.calendar.authorize"),
            eq(AgentRunContext.RequestSource.WEB),
            eq(AgentRunContext.Mode.EXCLUSIVE),
            eq(false),
            eq("key"),
            anyString(),
            any()))
        .thenAnswer(
            call -> {
              ((java.util.function.Consumer<AgentRunContext>) call.getArgument(8)).accept(context);
              return context;
            });
    var calendar = mock(GoogleCalendarService.class);
    var tasks = mock(TaskService.class);
    doAnswer(
            call -> {
              assertEquals(context, AgentRunScope.current());
              return null;
            })
        .when(calendar)
        .authorize();
    try (var runs = new RunService(registry, bus, events, cancellation, router::cancelQueued)) {
      var service =
          new IntegrationRunSubmitService(
              projects,
              lifecycle,
              registry,
              runs,
              router,
              cancellation,
              events,
              bus,
              calendar,
              tasks);
      assertEquals(context, service.authorizeCalendar(project.id(), "session", "key"));
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(calendar, tasks);
      pending.remove().run();
      verify(calendar).authorize();
      assertEquals(RunStatus.COMPLETED, runs.get("run").status());
      assertNull(AgentRunScope.current());
      assertThrows(
          IllegalArgumentException.class,
          () -> service.authorizeTasks(project.id(), "session", "invalid key"));
    }
  }
}
