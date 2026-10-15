package dev.mikoto2000.rei.application.run;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.memory.command.MemoryCommandSupport;
import dev.mikoto2000.rei.memory.service.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryRunSubmitServiceTest {
  @TempDir Path directory;

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
