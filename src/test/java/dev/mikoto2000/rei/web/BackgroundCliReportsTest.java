package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.run.BackgroundRunSubmitService;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.execution.BackgroundCommands;
import dev.mikoto2000.rei.core.execution.ExecutionScope;
import dev.mikoto2000.rei.core.execution.ExecutionType;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectRunStateStore;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.image.ImageGenerationRequest;
import dev.mikoto2000.rei.image.ImageGenerationResult;
import dev.mikoto2000.rei.image.ImageGenerationService;
import dev.mikoto2000.rei.image.ImageProperties;
import dev.mikoto2000.rei.image.ImageSize;
import dev.mikoto2000.rei.summarize.SummaryResult;
import dev.mikoto2000.rei.summarize.WebPageSummarizerService;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** バックグラウンド軽量CLI伝えるの結果と状態の契約を検証するテスト。 */
class BackgroundCliReportsTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** Web APIによる操作の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
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

  /** Web APIによる操作で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
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
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
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
