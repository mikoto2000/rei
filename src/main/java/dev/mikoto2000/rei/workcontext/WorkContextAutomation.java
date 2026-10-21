package dev.mikoto2000.rei.workcontext;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.WorkContextPayload;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 作業文脈の引き継ぎに必要な作業文脈Automationを扱う。
 *
 * <p>Queued only after durable terminal metadata; errors cannot change the original run outcome.
 */
@Component
public class WorkContextAutomation implements AutoCloseable {
  /** サービス。 */
  private final WorkContextService service;

  /** この機能の動作設定。 */
  private final WorkContextProperties properties;

  /** イベント一覧。 */
  private final AgentEventPublisher events;

  /** 実行の担当。 */
  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(256),
          runnable -> {
            var thread = new Thread(runnable, "rei-work-context");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());

  /** 指定された値や利用先を使って、WorkContextAutomationを初期化する。 */
  public WorkContextAutomation(
      WorkContextService service, WorkContextProperties properties, AgentEventPublisher events) {
    this.service = service;
    this.properties = properties;
    this.events = events;
  }

  /** 実行結果を後続の処理へ渡すため、開始を反映する。 */
  public void afterStart(AgentRunContext context) {
    try {
      service.captureRunStart(context);
    } catch (RuntimeException error) {
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(error);
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn(
              "Work-time Git metadata unavailable: {}",
              dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.classify(error).display());
    }
  }

  /** 実行結果を後続の処理へ渡すため、終端を反映する。 */
  public void afterTerminal(AgentRunContext context) {
    if (!properties.autoUpdate() || context.projectId() == null) return;
    try {
      worker.execute(
          () -> {
            publish(context, AgentEventType.WORK_CONTEXT_UPDATE_STARTED, null, "started");
            try {
              var result = service.update(context.conversationId(), context.runId());
              publish(
                  context,
                  AgentEventType.WORK_CONTEXT_UPDATED,
                  result.map(WorkContext::revision).orElse(null),
                  "completed");
            } catch (RuntimeException error) {
              var diagnostic =
                  dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.classify(error);
              publish(
                  context,
                  AgentEventType.WORK_CONTEXT_UPDATE_FAILED,
                  null,
                  diagnostic.display()
                      + ("timeout".equals(diagnostic.code())
                          ? " 設定: rei.work-context.timeout-seconds。"
                          : "")
                      + " 元 Run の結果には影響しません。/work show で保存状態を確認し、必要なら /work update を実行してください。");
            }
          });
    } catch (RejectedExecutionException error) {
      publish(
          context,
          AgentEventType.WORK_CONTEXT_UPDATE_FAILED,
          null,
          dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.classify(error).display()
              + " /work update で手動更新できます。");
    }
  }

  /** 状態や結果を購読先へ通知する。 */
  private void publish(AgentRunContext owner, AgentEventType type, Long revision, String status) {
    try {
      events.publish(
          new AgentEvent(
              UUID.randomUUID().toString(),
              0,
              Instant.now(),
              type,
              1,
              owner.conversationId(),
              null,
              null,
              owner.runId(),
              null,
              new WorkContextPayload(owner.runId(), revision, status),
              owner.projectId()));
    } catch (RuntimeException error) {
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("Work Context event delivery unavailable");
    }
  }

  /** 作業文脈の引き継ぎで利用した資源や登録を解放し、利用を終了する。 */
  @jakarta.annotation.PreDestroy
  @Override
  public void close() {
    worker.shutdown();
    try {
      if (!worker.awaitTermination(2, TimeUnit.SECONDS)) worker.shutdownNow();
    } catch (InterruptedException error) {
      worker.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
