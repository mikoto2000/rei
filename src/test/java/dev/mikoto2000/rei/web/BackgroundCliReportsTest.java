package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.execution.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.image.*;
import dev.mikoto2000.rei.summarize.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackgroundCliReportsTest {
  @TempDir Path directory;

  @Test
  void failedOrCancelledImagesNeverReplaceLastSuccessfulImage() throws Exception {
    var projects = new ProjectRegistry(directory.resolve("projects.json"));
    var project = projects.resolve(directory);
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
    var images = mock(ImageGenerationService.class);
    try (var runs = new RunService(registry, bus, events, cancellation, router::cancelQueued)) {
      var service =
          new BackgroundRunSubmitService(
              projects,
              registry,
              runs,
              router,
              cancellation,
              events,
              bus,
              mock(WebPageSummarizerService.class),
              images,
              new ImageProperties());
      var states = new ProjectRunStateStore(directory.resolve("state"));
      service.setShellState(states, mock(BackgroundCommands.class));
      when(images.generate(any())).thenReturn(ImageGenerationResult.failure("provider failure"));
      var failed = service.imageForCli(project.id(), "prompt", null, null, null, false);
      pending.remove().run();
      assertEquals(RunStatus.FAILED, runs.get(failed.runId()).status());
      assertTrue(states.latestCompleted(project.id(), ExecutionType.IMAGE).isEmpty());
      when(images.generate(any()))
          .thenAnswer(
              call -> {
                runs.cancel(AgentRunScope.current().runId());
                Thread.interrupted();
                return ImageGenerationResult.success(
                    directory.resolve("cancelled.png"), "cancelled");
              });
      var cancelled = service.imageForCli(project.id(), "prompt", null, null, null, false);
      pending.remove().run();
      assertEquals(RunStatus.CANCELLED, runs.get(cancelled.runId()).status());
      assertTrue(states.latestCompleted(project.id(), ExecutionType.IMAGE).isEmpty());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void imageHonorsExplicitOptionsInItsProjectExecutionAndPersistsSummaryWithoutSession()
      throws Exception {
    var projects = new ProjectRegistry(directory.resolve("projects.json"));
    var project = projects.resolve(directory);
    var registry = new RunRegistry(Clock.systemUTC());
    var bus = new InMemoryAgentEventBus();
    var events = new AgentEventFactory(Clock.systemUTC());
    var cancellation = new CommandCancellationService();
    var pending = new ArrayDeque<Runnable>();
    var router =
        new ConversationInputRouter(
            pending::add,
            (context, prompt, queue) -> {
              throw new AssertionError("Background must not run chat");
            });
    var images = mock(ImageGenerationService.class);
    var summaries = mock(WebPageSummarizerService.class);
    var output = directory.resolve("picture.png");
    when(images.generate(any()))
        .thenAnswer(
            call -> {
              ImageGenerationRequest request = call.getArgument(0);
              assertEquals(output, request.outputPath());
              assertEquals("image-test", request.model());
              assertFalse(request.enhancePrompt());
              assertEquals(new ImageSize(512, 768), request.size());
              assertEquals(directory, ExecutionScope.current().projectRoot());
              return ImageGenerationResult.success(output, "raw prompt");
            });
    when(summaries.summarize(any()))
        .thenAnswer(call -> new SummaryResult(call.getArgument(0), "summary", null));
    try (var runs = new RunService(registry, bus, events, cancellation, router::cancelQueued)) {
      var service =
          new BackgroundRunSubmitService(
              projects,
              registry,
              runs,
              router,
              cancellation,
              events,
              bus,
              summaries,
              images,
              new ImageProperties());
      var states = new ProjectRunStateStore(directory.resolve("state"));
      service.setShellState(states, mock(BackgroundCommands.class));
      var image =
          service.imageForCli(
              project.id(), "raw prompt", output.toString(), "image-test", "512x768", true);
      verifyNoInteractions(images);
      pending.remove().run();
      assertEquals(RunStatus.COMPLETED, runs.get(image.runId()).status());
      assertEquals("", image.conversationId());
      assertTrue(
          states
              .latestCompleted(project.id(), ExecutionType.IMAGE)
              .orElseThrow()
              .result()
              .contains("画像を保存しました"));
      var summary = service.summaryForCli(project.id(), "https://example.com");
      pending.remove().run();
      assertEquals(
          "summary",
          states.latestCompleted(project.id(), ExecutionType.SUMMARIZE).orElseThrow().result());
      assertEquals(RunStatus.COMPLETED, runs.get(summary.runId()).status());
      assertNull(ExecutionScope.current());
    }
  }
}
