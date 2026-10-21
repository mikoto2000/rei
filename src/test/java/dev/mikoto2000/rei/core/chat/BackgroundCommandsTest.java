package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.execution.BackgroundCommands;
import dev.mikoto2000.rei.core.execution.ExecutionScope;
import dev.mikoto2000.rei.core.execution.ExecutionType;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectRunStateStore;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.image.ImageGenerationRequest;
import dev.mikoto2000.rei.image.ImageGenerationResult;
import dev.mikoto2000.rei.image.ImageGenerationService;
import dev.mikoto2000.rei.image.ImageProperties;
import dev.mikoto2000.rei.image.ImageSize;
import dev.mikoto2000.rei.summarize.SummaryResult;
import dev.mikoto2000.rei.summarize.WebPageSummarizerService;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** バックグラウンドコマンド一覧の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class BackgroundCommandsTest extends dev.mikoto2000.rei.core.project.ProjectClientTestSupport {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temp;

  /** 検証に使うa、b。 */
  ProjectContext a, b;

  /** 検証に使うプロジェクト一覧。 */
  ProjectService projects;

  /** 検証に使う状態一覧。 */
  ProjectRunStateStore states;

  /** 検証に使うタスク一覧。 */
  List<Runnable> tasks;

  /** 通知された出来事と順序を検証するための記録。 */
  List<AgentEvent> events;

  /** 検証に使う入力の振り分け。 */
  ConversationInputRouter router;

  /** 検証に使う要約一覧。 */
  WebPageSummarizerService summaries;

  /** 検証に使う画像一覧。 */
  ImageGenerationService images;

  /** 検証に使うコマンド一覧。 */
  BackgroundCommands commands;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() throws Exception {
    var registry = new ProjectRegistry(temp.resolve("projects.json"));
    a = registry.resolve(Files.createDirectory(temp.resolve("A")));
    b = registry.resolve(Files.createDirectory(temp.resolve("B")));
    projects = connect(new ProjectService(a.root(), registry));
    states = new ProjectRunStateStore(temp);
    tasks = new ArrayList<>();
    events = new ArrayList<>();
    router = new ConversationInputRouter(tasks::add, (c, p, q) -> {});
    summaries = mock(WebPageSummarizerService.class);
    images = mock(ImageGenerationService.class);
    commands =
        new BackgroundCommands(
            router,
            projects,
            states,
            summaries,
            images,
            new AgentEventFactory(Clock.systemUTC()),
            events::add,
            Clock.systemUTC());
  }

  /** 検証用に、利用者が対象の情報を確認できるように表示する。 */
  String show() {
    var out = new ArrayList<String>();
    commands.showLastSummary(out::add);
    return String.join("\n", out);
  }

  /** 会話の実行で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void narratesOnlyWhenSavedSummaryIsDisplayedForCurrentProject() {
    var narrator = mock(dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator.class);
    commands.setNarrator(narrator);
    when(summaries.summarize(any()))
        .thenReturn(new SummaryResult(URI.create("https://a.example"), "summary A", null));
    show();
    assertThat(tasks).isEmpty();
    commands.summarize(URI.create("https://a.example"));
    show(); // No completed summary yet.
    assertThat(tasks).hasSize(1);
    tasks.removeFirst().run();
    assertThat(tasks).isEmpty(); // Completion must not enqueue audio.
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(narrator);
    projects.cd(b.root().toString());
    show();
    assertThat(tasks).isEmpty();
    projects.cd(a.root().toString());
    assertThat(show()).contains("summary A");
    assertThat(router.activeExecutions()).isEmpty();
    assertThat(tasks).hasSize(1);
    projects.cd(b.root().toString());
    tasks.removeFirst().run();
    verify(narrator).narrateCompletedRun("summary A");
    verifyNoMoreInteractions(narrator);
  }

  /** 会話の実行で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void snapshotsOwnershipPersistsLatestPerProjectAndBareDisplayDoesNotStartWork() {
    when(summaries.summarize(any()))
        .thenAnswer(
            call -> {
              var execution = ExecutionScope.current();
              assertThat(dev.mikoto2000.rei.llm.ConversationIds.currentChat())
                  .isEqualTo(execution.conversationId());
              assertThat(ProjectService.contextForOperation().id())
                  .isEqualTo(execution.projectId());
              return new SummaryResult(
                  call.getArgument(0),
                  execution.projectId().equals(a.id()) ? "summary A" : "summary B",
                  null);
            });
    assertThat(show()).contains("No completed summary", "A");
    assertThat(tasks).isEmpty();
    commands.summarize(URI.create("https://a.example"));
    assertThat(show()).contains("currently running", "https://a.example", "No completed summary");
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(summaries);
    projects.cd(b.root().toString());
    tasks.getFirst().run();
    assertThat(router.activeExecutions()).isEmpty();
    assertThat(show()).contains("No completed summary", "B").doesNotContain("summary A");
    commands.summarize(URI.create("https://b.example"));
    tasks.get(1).run();
    assertThat(show()).contains("summary B").doesNotContain("summary A");
    projects.cd(a.root().toString());
    commands.summarize(URI.create("https://new.example"));
    assertThat(show()).contains("currently running", "summary A", "https://new.example");
    assertThat(tasks).hasSize(3);
    assertThat(
            new ProjectRunStateStore(temp)
                .latestCompleted(a.id(), ExecutionType.SUMMARIZE)
                .orElseThrow()
                .result())
        .isEqualTo("summary A");
    assertThat(events.stream().filter(e -> e.type() == AgentEventType.EXECUTION_COMPLETED))
        .allMatch(e -> e.projectId() != null);
  }

  /** 画像について、所有権を保持することを確認する。 */
  @Test
  void imagePreservesOwnershipWithoutChangingSummaryOrConversation() {
    when(images.generate(any()))
        .thenAnswer(
            call -> {
              assertThat(ExecutionScope.current().projectId()).isEqualTo(a.id());
              assertThat(AgentRunScope.current()).isNull();
              return ImageGenerationResult.success(a.root().resolve("image.png"), "picture");
            });
    commands.image(
        new ImageGenerationRequest(
            "picture", Path.of("image.png"), null, new ImageSize(1024, 1024)));
    projects.cd(b.root().toString());
    assertThat(router.activeExecutions()).hasSize(1);
    tasks.getFirst().run();
    assertThat(router.activeExecutions()).isEmpty();
    assertThat(states.latestCompleted(a.id(), ExecutionType.IMAGE).orElseThrow().result())
        .contains("image.png");
    assertThat(states.latestCompleted(b.id(), ExecutionType.IMAGE)).isEmpty();
    assertThat(states.latestCompleted(a.id(), ExecutionType.SUMMARIZE)).isEmpty();
  }

  /** 会話の実行で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void failuresAndTimeoutsNotifyAndNeverReplaceSuccessfulResult() {
    when(summaries.summarize(any()))
        .thenReturn(new SummaryResult(URI.create("https://ok.example"), "good", null));
    commands.summarize(URI.create("https://ok.example"));
    tasks.removeFirst().run();
    for (RuntimeException error :
        List.of(
            new IllegalStateException("broken"),
            new java.util.concurrent.CancellationException(),
            new java.util.concurrent.CompletionException(
                new java.util.concurrent.TimeoutException()))) {
      doThrow(error).when(summaries).summarize(any());
      commands.summarize(URI.create("https://fail.example"));
      tasks.removeFirst().run();
      assertThat(router.activeExecutions()).isEmpty();
      assertThat(show()).contains("good");
    }
    when(images.generate(any())).thenReturn(ImageGenerationResult.failure("timeout"));
    commands.image(new ImageGenerationRequest("picture", null, null, new ImageSize(1024, 1024)));
    tasks.removeFirst().run();
    assertThat(router.activeExecutions()).isEmpty();
    assertThat(events)
        .anyMatch(e -> e.type() == AgentEventType.EXECUTION_FAILED)
        .anyMatch(e -> e.type() == AgentEventType.EXECUTION_CANCELLED);
    assertThat(new ConversationInputRouter(tasks::add, (c, p, q) -> {}).activeExecutions())
        .isEmpty();
  }

  /** 会話の実行で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void shellCommandsReturnImmediatelyAndSnapshotParsedArguments() {
    when(summaries.summarize(any()))
        .thenAnswer(call -> new SummaryResult(call.getArgument(0), "summary", null));
    when(images.generate(any()))
        .thenReturn(ImageGenerationResult.success(temp.resolve("image.png"), "picture"));
    var summarize = new dev.mikoto2000.rei.summarize.command.SummarizeCommand(summaries);
    summarize.setBackgroundCommands(commands);
    var summaryCli = new picocli.CommandLine(summarize);
    assertThat(summaryCli.execute("https://one.example")).isZero();
    assertThat(summaryCli.execute("https://two.example")).isZero();
    var generate =
        new dev.mikoto2000.rei.image.command.GenerateCommand(
            images,
            new ImageProperties(),
            new dev.mikoto2000.rei.core.service.CommandCancellationService());
    generate.setBackgroundCommands(commands);
    var imageCli = new picocli.CommandLine(generate);
    assertThat(imageCli.execute("--raw", "first picture")).isZero();
    assertThat(imageCli.execute("second picture")).isZero();
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(summaries, images);
    assertThat(tasks).hasSize(4);
    tasks.forEach(Runnable::run);
    verify(summaries).summarize(URI.create("https://one.example"));
    verify(summaries).summarize(URI.create("https://two.example"));
    verify(images).generate(argThat(r -> r.prompt().equals("first picture") && !r.enhancePrompt()));
    verify(images).generate(argThat(r -> r.prompt().equals("second picture") && r.enhancePrompt()));
    int started = tasks.size();
    var text = new java.io.StringWriter();
    summaryCli.setOut(new java.io.PrintWriter(text));
    assertThat(summaryCli.execute()).isZero();
    assertThat(text.toString()).contains("Last completed summary", "summary");
    assertThat(tasks).hasSize(started);
  }
}
