package dev.mikoto2000.rei.application.run;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.image.ImageGenerationRequest;
import dev.mikoto2000.rei.image.ImageGenerationResult;
import dev.mikoto2000.rei.image.ImageGenerationService;
import dev.mikoto2000.rei.image.ImageProperties;
import dev.mikoto2000.rei.image.ImageSize;
import dev.mikoto2000.rei.summarize.WebPageSummarizerService;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * バックグラウンド実行受付のユースケースを実現する。
 *
 * <p>Explicit non-conversational operations over the existing Run lifecycle and shared project
 * queue.
 */
public final class BackgroundRunSubmitService {
  /** プロジェクト一覧。 */
  private final Function<String, Optional<ProjectContext>> findProject;

  /** 登録台帳。 */
  private final java.util.function.Consumer<AgentRunContext> registerRun;

  private final java.util.function.Consumer<String> forgetRun;
  private final java.util.function.Function<String, RunSnapshot> registeredRun;

  /** 実行一覧。 */
  private final RunService runs;

  /** 入力の振り分け。 */
  private final BiConsumer<AgentRunContext, Runnable> submitOperation;

  /** キャンセル。 */
  private final java.util.function.Supplier<Runnable> beginCommand;

  private final java.util.function.BooleanSupplier cancellationRequested;
  private final java.util.function.Predicate<String> runCancellationRequested;

  /** イベント一覧。 */
  private final AgentEventFactory events;

  /** 通知の連携先。 */
  private final AgentEventBus bus;

  /** 要約一覧。 */
  private final WebPageSummarizerService summaries;

  /** 画像一覧。 */
  private final Function<ImageGenerationRequest, ImageGenerationResult> generateImage;

  /** この機能の動作設定。 */
  private final Supplier<String> defaultImageSize;

  /** 状態一覧。 */
  private dev.mikoto2000.rei.core.project.ProjectRunStateStore states;

  /** バックグラウンド。 */
  private java.util.function.BiFunction<
          dev.mikoto2000.rei.core.project.ProjectContext,
          java.util.List<dev.mikoto2000.rei.core.execution.ActiveExecution>,
          String>
      renderLatestSummary;

  /** 実行の受付と状態管理で使用するシェル状態を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setShellState(
      dev.mikoto2000.rei.core.project.ProjectRunStateStore states,
      dev.mikoto2000.rei.core.execution.BackgroundCommands background) {
    this.states = states;
    this.renderLatestSummary =
        background == null
            ? null
            : (project, active) -> {
              var output = new java.util.ArrayList<String>();
              background.showLastSummary(project, output::add, false, active);
              return String.join(System.lineSeparator(), output);
            };
  }

  /** 活動中要約一覧。 */
  private final java.util.concurrent.ConcurrentMap<String, String> activeSummaries =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** 実行の受付と状態管理で用いる要約対象軽量CLIを返す。 */
  public AgentRunContext summaryForCli(String projectId, String url) {
    requireText(url, 4096);
    var source = URI.create(url);
    if (!("https".equalsIgnoreCase(source.getScheme())
            || "http".equalsIgnoreCase(source.getScheme()))
        || source.getHost() == null
        || source.getUserInfo() != null) throw new IllegalArgumentException("Invalid URL");
    return submit(
        projectId,
        context -> {
          activeSummaries.put(context.runId(), url);
          try {
            String result = summaries.summarize(source).summary();
            save(context, dev.mikoto2000.rei.core.execution.ExecutionType.SUMMARIZE, url, result);
            return result;
          } finally {
            activeSummaries.remove(context.runId());
          }
        });
  }

  /** 実行の受付と状態管理で用いる最新要約対象軽量CLIを返す。 */
  public String latestSummaryForCli(String projectId) {
    var project =
        findProject.apply(projectId).orElseThrow(() -> new ResourceNotFoundException("Project"));
    if (renderLatestSummary == null)
      throw new IllegalStateException("Background runtime unavailable");
    var active =
        activeSummaries.entrySet().stream()
            .map(
                item -> {
                  var run = registeredRun.apply(item.getKey());
                  return new dev.mikoto2000.rei.core.execution.ActiveExecution(
                      item.getKey(),
                      projectId,
                      "",
                      project.root(),
                      dev.mikoto2000.rei.core.execution.ExecutionType.SUMMARIZE,
                      item.getValue(),
                      run.startedAt());
                })
            .filter(item -> registeredRun.apply(item.id()).context().projectId().equals(projectId))
            .toList();
    return renderLatestSummary.apply(project, active);
  }

  /** 実行の受付と状態管理で用いる画像対象軽量CLIを返す。 */
  public AgentRunContext imageForCli(
      String projectId, String prompt, String output, String model, String size, boolean raw) {
    requireText(prompt, 10000);
    if (output != null) {
      requireText(output, 16384);
      if (!java.nio.file.Path.of(output).isAbsolute())
        throw new IllegalArgumentException("Absolute image output required");
    }
    if (model != null) requireText(model, 512);
    var dimensions = ImageSize.parse(size == null ? defaultImageSize.get() : size);
    return submit(
        projectId,
        context -> {
          var execution =
              new dev.mikoto2000.rei.core.execution.ActiveExecution(
                  context.runId(),
                  context.projectId(),
                  "",
                  context.projectRoot(),
                  dev.mikoto2000.rei.core.execution.ExecutionType.IMAGE,
                  prompt,
                  registeredRun.apply(context.runId()).startedAt());
          try (var scope = dev.mikoto2000.rei.core.execution.ExecutionScope.open(execution)) {
            var result =
                generateImage.apply(
                    new ImageGenerationRequest(
                        prompt,
                        output == null ? null : java.nio.file.Path.of(output),
                        model,
                        dimensions,
                        !raw));
            if (!result.success()) {
              if ("cancelled".equals(result.message()))
                throw new java.util.concurrent.CancellationException();
              bus.publish(
                  events
                      .messageDelta(
                          context.runId(),
                          dev.mikoto2000.rei.event.CredentialRedactor.redact(
                              "[error] 画像生成に失敗しました: " + result.message()))
                      .withOwnership(context));
              throw new IllegalStateException("Image generation failed");
            }
            String report = "画像生成プロンプト: " + result.prompt() + "\n画像を保存しました: " + result.savedPath();
            save(context, dev.mikoto2000.rei.core.execution.ExecutionType.IMAGE, prompt, report);
            return report;
          }
        });
  }

  /** 後から参照できるよう、指定された情報を保存する。 */
  private void save(
      AgentRunContext context,
      dev.mikoto2000.rei.core.execution.ExecutionType type,
      String source,
      String result) {
    if (runCancellationRequested.test(context.runId())
        || Thread.currentThread().isInterrupted()
        || registeredRun.apply(context.runId()).status() == RunStatus.CANCELLED)
      throw new java.util.concurrent.CancellationException();
    if (states != null)
      states.saveCompleted(
          new dev.mikoto2000.rei.core.execution.CompletedExecution(
              context.runId(),
              context.projectId(),
              "",
              type,
              source,
              result,
              registeredRun.apply(context.runId()).startedAt(),
              java.time.Instant.now()));
  }

  /** 指定された値や利用先を使って、BackgroundRunSubmitServiceを初期化する。 */
  public BackgroundRunSubmitService(
      ProjectRegistry projects,
      RunRegistry registry,
      RunService runs,
      ConversationInputRouter router,
      CommandCancellationService cancellation,
      AgentEventFactory events,
      AgentEventBus bus,
      WebPageSummarizerService summaries,
      ImageGenerationService images,
      ImageProperties properties) {
    this.findProject = id -> projects.resolveById(id);
    this.registerRun = context -> registry.register(context);
    this.forgetRun = run -> registry.forget(run);
    this.registeredRun = run -> registry.get(run);
    this.runs = runs;
    this.submitOperation =
        (context, operation) ->
            router.submitOperation(context, operation, work -> runs.execute(context, work));
    this.beginCommand =
        () -> {
          cancellation.begin(Thread.currentThread());
          return cancellation::clear;
        };
    this.cancellationRequested = () -> cancellation.isCancellationRequested();
    this.runCancellationRequested = run -> cancellation.diagnostic(run).requestedAt() != null;
    this.events = events;
    this.bus = bus;
    this.summaries = summaries;
    this.generateImage = request -> images.generate(request);
    this.defaultImageSize = () -> properties.getSize();
  }

  /** 実行の受付と状態管理で用いる要約を返す。 */
  public AgentRunContext summary(String projectId, String url) {
    requireText(url, 4096);
    URI source = URI.create(url);
    if (!("https".equalsIgnoreCase(source.getScheme())
            || "http".equalsIgnoreCase(source.getScheme()))
        || source.getHost() == null
        || source.getUserInfo() != null) throw new IllegalArgumentException("Invalid URL");
    return submit(projectId, context -> summaries.summarize(source).summary());
  }

  /** 実行の受付と状態管理で用いる画像を返す。 */
  public AgentRunContext image(String projectId, String prompt, String size) {
    requireText(prompt, 10000);
    var dimensions = ImageSize.parse(size == null ? defaultImageSize.get() : size);
    return submit(
        projectId,
        context -> {
          // Server-selected destination: HTTP never receives an output path or model configuration.
          var output =
              context.projectRoot().resolve(".rei/web-images").resolve(context.runId() + ".png");
          var result =
              generateImage.apply(
                  new ImageGenerationRequest(prompt, output, null, dimensions, true));
          if (!result.success()) {
            if ("cancelled".equals(result.message()))
              throw new java.util.concurrent.CancellationException();
            throw new IllegalStateException("Image generation failed");
          }
          return result.artifactId() == null
              ? ".rei/web-images/" + context.runId() + ".png"
              : "Artifact: " + result.artifactId();
        });
  }

  /** 条件を満たさない操作を拒否するため、テキストの前提条件を確認する。 */
  private static void requireText(String text, int max) {
    if (text == null || text.isBlank() || text.length() > max)
      throw new IllegalArgumentException("Invalid input");
  }

  /** 依頼を受け付け、実行を担当する処理へ引き渡す。 */
  private AgentRunContext submit(String projectId, Function<AgentRunContext, String> operation) {
    requireText(projectId, 128);
    var project =
        findProject.apply(projectId).orElseThrow(() -> new ResourceNotFoundException("Project"));
    // Empty internal conversation identity denotes an operation, with no persisted Session or Turn.
    var context =
        new AgentRunContext(
            UUID.randomUUID().toString(),
            "",
            project.root(),
            project.id(),
            AgentRunContext.RequestSource.WEB);
    registerRun.accept(context);
    try {
      submitOperation.accept(context, () -> execute(context, operation));
    } catch (RuntimeException | Error error) {
      forgetRun.accept(context.runId());
      throw error;
    }
    return context;
  }

  /** 要求された処理を実行する。 */
  private void execute(AgentRunContext context, Function<AgentRunContext, String> operation) {
    try (var scope = AgentRunScope.open(context)) {
      Runnable clearCancellation = beginCommand.get();
      try {
        if (cancellationRequested.getAsBoolean()) return;
        bus.publish(events.runStarted(context.runId(), "operation", null).withOwnership(context));
        String result = operation.apply(context);
        if (cancellationRequested.getAsBoolean() || Thread.currentThread().isInterrupted()) return;
        bus.publish(events.messageDelta(context.runId(), result).withOwnership(context));
        bus.publish(events.runCompleted(context.runId(), 0).withOwnership(context));
      } catch (java.util.concurrent.CancellationException error) {
        runs.cancel(context.runId());
      } finally {
        clearCancellation.run();
      }
    }
  }
}
