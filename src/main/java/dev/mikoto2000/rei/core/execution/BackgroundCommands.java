package dev.mikoto2000.rei.core.execution;

import dev.mikoto2000.rei.conversation.HistoryFormatter;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRunStateStore;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.event.CredentialRedactor;
import dev.mikoto2000.rei.image.ImageGenerationRequest;
import dev.mikoto2000.rei.image.ImageGenerationService;
import dev.mikoto2000.rei.summarize.WebPageSummarizerService;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * 処理の実行制御に必要なバックグラウンドコマンド一覧を扱う。
 *
 * <p>Command orchestration over the existing runtime registry/executor and project state store.
 */
@Service
public class BackgroundCommands {
  /** 実行一覧。 */
  private final ConversationInputRouter runs;

  /** プロジェクト一覧。 */
  private final ProjectService projects;

  /** 状態一覧。 */
  private final ProjectRunStateStore states;

  /** 要約一覧。 */
  private final WebPageSummarizerService summaries;

  /** 画像一覧。 */
  private final ImageGenerationService images;

  /** イベント一覧。 */
  private final AgentEventFactory events;

  /** 通知先。 */
  private final AgentEventPublisher publisher;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 読み上げの案内。 */
  private dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator narrator;

  /** 処理の実行制御で使用する読み上げの案内を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setNarrator(dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator narrator) {
    this.narrator = narrator;
  }

  /** 指定された値や利用先を使って、BackgroundCommandsを初期化する。 */
  public BackgroundCommands(
      ConversationInputRouter runs,
      ProjectService projects,
      ProjectRunStateStore states,
      WebPageSummarizerService summaries,
      ImageGenerationService images,
      AgentEventFactory events,
      AgentEventPublisher publisher,
      Clock clock) {
    this.runs = runs;
    this.projects = projects;
    this.states = states;
    this.summaries = summaries;
    this.images = images;
    this.events = events;
    this.publisher = publisher;
    this.clock = clock;
  }

  /** 対象の内容を、要点が分かる形にまとめる。 */
  public ActiveExecution summarize(URI source) {
    return start(
        ExecutionType.SUMMARIZE, source.toString(), () -> summaries.summarize(source).summary());
  }

  /** 処理の実行制御で用いる画像を返す。 */
  public ActiveExecution image(ImageGenerationRequest request) {
    return start(
        ExecutionType.IMAGE,
        request.prompt(),
        () -> {
          var result = images.generate(request);
          if (!result.success()) {
            if ("cancelled".equals(result.message()))
              throw new java.util.concurrent.CancellationException();
            throw new IllegalStateException(result.message());
          }
          return "画像生成プロンプト: " + result.prompt() + "\n画像を保存しました: " + result.savedPath();
        });
  }

  /** 要求された処理を開始する。 */
  private ActiveExecution start(ExecutionType type, String source, Supplier<String> operation) {
    var project = projects.currentContext();
    var registered = new java.util.concurrent.atomic.AtomicReference<ActiveExecution>();
    try {
      return runs.submitBackground(
          project,
          type,
          source,
          e -> {
            registered.set(e);
            publish(e, "RUNNING", "");
          },
          e -> {
            try {
              String result = operation.get();
              if (Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException();
              states.saveCompleted(CompletedExecution.of(e, source, result, clock.instant()));
              publish(e, "COMPLETED", result);
            } catch (Exception error) {
              publish(
                  e,
                  status(error),
                  new HistoryFormatter()
                      .label(dev.mikoto2000.rei.event.FailureMessages.describe(error)));
              org.slf4j.LoggerFactory.getLogger(getClass())
                  .debug("Background execution failed: {}", e.id(), error);
            }
          });
    } catch (RuntimeException error) {
      if (registered.get() != null)
        publish(
            registered.get(),
            "FAILED",
            "Execution could not be started: "
                + dev.mikoto2000.rei.event.FailureMessages.describe(error));
      throw error;
    }
  }

  /** 対象の現在の状態を、照会用の情報として返す。 */
  private String status(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.util.concurrent.CancellationException
          || cause instanceof InterruptedException) return "CANCELLED";
      if (cause instanceof java.util.concurrent.TimeoutException
          || cause instanceof java.net.http.HttpTimeoutException
          || cause instanceof java.net.SocketTimeoutException) return "TIMED_OUT";
    }
    return Thread.currentThread().isInterrupted()
        ? "CANCELLED"
        : error.getMessage() != null
                && error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("timeout")
            ? "TIMED_OUT"
            : "FAILED";
  }

  /** 状態や結果を購読先へ通知する。 */
  private void publish(ActiveExecution execution, String status, String output) {
    try {
      publisher.publish(events.backgroundExecution(execution, status, output));
    } catch (RuntimeException error) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .debug("Execution notification failed: {}", execution.id(), error);
    }
  }

  /** 利用者が内容を確認できるよう、直近要約を表示する。 */
  public void showLastSummary(Consumer<String> out) {
    showLastSummary(projects.currentContext(), out, true, java.util.List.of());
  }

  /** 利用者が内容を確認できるよう、直近要約を表示する。 */
  public void showLastSummary(
      ProjectContext project,
      Consumer<String> out,
      boolean narrate,
      java.util.List<ActiveExecution> additional) {
    var format = new HistoryFormatter();
    var active =
        java.util.stream.Stream.concat(runs.activeExecutions().stream(), additional.stream())
            .filter(e -> e.type() == ExecutionType.SUMMARIZE && project.id().equals(e.projectId()))
            .toList();
    if (!active.isEmpty()) {
      out.accept("Summarize is currently running:");
      for (var execution : active) {
        long elapsed =
            Math.max(0, Duration.between(execution.startedAt(), clock.instant()).toSeconds());
        out.accept(
            "  "
                + execution.id()
                + "  Source: "
                + format.label(execution.summary())
                + "  Elapsed: "
                + String.format("%02d:%02d", elapsed / 60, elapsed % 60));
      }
    }
    var latest = states.latestCompleted(project.id(), ExecutionType.SUMMARIZE);
    if (latest.isEmpty())
      out.accept("No completed summary for project: " + format.label(project.name()));
    else {
      var result = latest.get();
      out.accept("Last completed summary:");
      out.accept("  Source: " + format.label(result.source()));
      out.accept("  Completed: " + format.timestamp(result.completedAt().toString()));
      String text =
          CredentialRedactor.redact(result.result()).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
      out.accept(text);
      if (narrate && narrator != null && !text.isBlank()) {
        var execution =
            new ActiveExecution(
                result.id(),
                result.projectId(),
                result.conversationId(),
                project.root(),
                result.type(),
                result.source(),
                result.startedAt());
        try {
          runs.executeAuxiliary(
              () -> {
                try (var scope = ExecutionScope.open(execution)) {
                  narrator.narrateCompletedRun(text);
                }
              });
        } catch (java.util.concurrent.RejectedExecutionException error) {
          org.slf4j.LoggerFactory.getLogger(getClass())
              .debug("Summary narration could not be scheduled", error);
        }
      }
    }
  }
}
