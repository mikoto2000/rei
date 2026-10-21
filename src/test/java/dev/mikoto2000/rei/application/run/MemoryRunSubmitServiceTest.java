package dev.mikoto2000.rei.application.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.memory.command.MemoryCommandSupport;
import dev.mikoto2000.rei.memory.service.SleepRun;
import dev.mikoto2000.rei.memory.service.SleepService;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 記憶実行受付サービスの結果と状態の契約を検証するテスト。 */
class MemoryRunSubmitServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 旧形式要約および統合について、操作実行待ちを利用することを確認する。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void legacySummaryAndConsolidationUseTheOperationQueueWithoutEnteringChat(
      boolean summaryOperation) {
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
              throw new AssertionError("No chat for memory maintenance");
            });
    var captured =
        new AgentRunContext(
            "run", "session", directory, project.id(), AgentRunContext.RequestSource.WEB);
    when(lifecycle.submit(
            eq(project),
            eq("session"),
            anyString(),
            eq(AgentRunContext.RequestSource.WEB),
            eq(AgentRunContext.Mode.EXCLUSIVE),
            eq(false),
            eq("receipt"),
            anyString(),
            any()))
        .thenAnswer(
            call -> {
              ((java.util.function.Consumer<AgentRunContext>) call.getArgument(8)).accept(captured);
              return captured;
            });
    var summary = mock(dev.mikoto2000.rei.memory.command.MemoryCommand.SummarizeCommand.class);
    var consolidation =
        mock(dev.mikoto2000.rei.memory.command.MemoryCommand.ConsolidateCommand.class);
    when(summary.render(true)).thenReturn("Summary saved");
    when(consolidation.render(true)).thenReturn("Consolidated");
    try (var runs = new RunService(registry, bus, events, cancellation, router::cancelQueued)) {
      var service =
          new MemoryRunSubmitService(
              projects,
              lifecycle,
              registry,
              runs,
              router,
              cancellation,
              events,
              bus,
              mock(SleepService.class),
              mock(MemoryCommandSupport.class));
      service.setSummary(summary);
      service.setConsolidation(consolidation);
      if (summaryOperation) service.summarize(project.id(), "session", true, "receipt");
      else service.consolidate(project.id(), "session", true, "receipt");
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(summary, consolidation);
      pending.remove().run();
      if (summaryOperation) {
        verify(summary).render(true);
        verifyNoInteractions(consolidation);
      } else {
        verify(consolidation).render(true);
        verifyNoInteractions(summary);
      }
      assertEquals(RunStatus.COMPLETED, runs.get("run").status());
    }
  }

  /** 実行の受付と状態管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void sleepIsQueuedWithCapturedSessionAndDoesNotEnterTheChatRunner() {
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
              throw new AssertionError("Memory operation must not enter chat");
            });
    var sleep = mock(SleepService.class);
    var support = mock(MemoryCommandSupport.class);
    var report =
        new SleepService.Report(
            new SleepRun(
                "sleep",
                "session",
                project.id(),
                "start",
                "end",
                "PREVIEW",
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0),
            List.of(),
            true);
    when(sleep.sleep("session", project.id(), true)).thenReturn(report);
    when(support.report(report)).thenReturn("Sleep Preview");
    var captured =
        new AgentRunContext(
            "run", "session", directory, project.id(), AgentRunContext.RequestSource.WEB);
    when(lifecycle.submit(
            eq(project),
            eq("session"),
            anyString(),
            eq(AgentRunContext.RequestSource.WEB),
            eq(AgentRunContext.Mode.EXCLUSIVE),
            eq(false),
            eq("receipt"),
            anyString(),
            any()))
        .thenAnswer(
            call -> {
              ((java.util.function.Consumer<AgentRunContext>) call.getArgument(8)).accept(captured);
              return captured;
            });
    try (var runs = new RunService(registry, bus, events, cancellation, router::cancelQueued)) {
      var service =
          new MemoryRunSubmitService(
              projects,
              lifecycle,
              registry,
              runs,
              router,
              cancellation,
              events,
              bus,
              sleep,
              support);
      assertEquals(captured, service.sleep(project.id(), "session", true, "receipt"));
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(sleep);
      assertEquals(RunStatus.QUEUED, runs.get("run").status());
      pending.remove().run();
      verify(sleep).sleep("session", project.id(), true);
      assertEquals(RunStatus.COMPLETED, runs.get("run").status());
      assertThrows(
          IllegalArgumentException.class,
          () -> service.sleep(project.id(), null, false, "receipt"));
      assertThrows(
          IllegalArgumentException.class,
          () -> service.sleep(project.id(), "session", false, "invalid key"));
    }
  }
}
