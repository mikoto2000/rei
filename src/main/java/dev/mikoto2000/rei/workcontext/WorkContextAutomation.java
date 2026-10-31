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
          4,
          4,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(256),
          runnable -> {
            var thread = new Thread(runnable, "rei-work-context");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());

  private final Object queueLock = new Object();
  private final java.util.Map<String, java.util.LinkedHashMap<String, AgentRunContext>> pending =
      new java.util.LinkedHashMap<>();
  private int queued;
  private boolean closed;

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
      synchronized (queueLock) {
        if (closed) throw new RejectedExecutionException("Work Context worker closed");
        var project = pending.get(context.projectId());
        String key = context.conversationId() + "\n" + context.runId();
        if (project != null && project.containsKey(key)) return;
        if (queued >= 256) throw new RejectedExecutionException("Work Context queue full");
        boolean launch = project == null;
        if (launch) {
          project = new java.util.LinkedHashMap<>();
          pending.put(context.projectId(), project);
        }
        project.put(key, context);
        queued++;
        if (launch) {
          try {
            worker.execute(() -> drain(context.projectId()));
          } catch (RejectedExecutionException error) {
            pending.remove(context.projectId());
            queued--;
            throw error;
          }
        }
      }
    } catch (RejectedExecutionException error) {
      failed(context, error);
    }
  }

  private void drain(String projectId) {
    boolean completed = false;
    try {
      while (!Thread.currentThread().isInterrupted()) {
        var batch = new java.util.ArrayList<AgentRunContext>();
        synchronized (queueLock) {
          var project = pending.get(projectId);
          if (project == null || project.isEmpty()) {
            pending.remove(projectId);
            completed = true;
            return;
          }
          String session = project.values().iterator().next().conversationId();
          var entries = project.entrySet().iterator();
          while (entries.hasNext() && batch.size() < Math.min(4, properties.maxTurns())) {
            var entry = entries.next();
            if (!entry.getValue().conversationId().equals(session)) continue;
            batch.add(entry.getValue());
            entries.remove();
            queued--;
          }
        }
        process(batch);
      }
    } finally {
      if (!completed) {
        java.util.List<AgentRunContext> abandoned;
        synchronized (queueLock) {
          var project = pending.remove(projectId);
          abandoned =
              project == null ? java.util.List.of() : java.util.List.copyOf(project.values());
          queued -= abandoned.size();
        }
        for (var owner : abandoned)
          failed(owner, new RejectedExecutionException("Work Context worker stopped"));
      }
    }
  }

  private void process(java.util.List<AgentRunContext> batch) {
    process(batch, true);
  }

  private void process(java.util.List<AgentRunContext> batch, boolean announce) {
    if (announce)
      for (var owner : batch)
        publish(owner, AgentEventType.WORK_CONTEXT_UPDATE_STARTED, null, "started");
    try {
      var first = batch.getFirst();
      var result =
          batch.size() == 1
              ? service.update(first.conversationId(), first.runId())
              : service.updateRuns(
                  first.conversationId(),
                  batch.stream()
                      .map(AgentRunContext::runId)
                      .collect(
                          java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
      for (var owner : batch)
        publish(
            owner,
            AgentEventType.WORK_CONTEXT_UPDATED,
            result.map(WorkContext::revision).orElse(null),
            "completed");
    } catch (WorkContextExtractionException error) {
      // Input rejection happens before a model request; splitting preserves all evidence.
      if (batch.size() > 1
          && error.reason() == WorkContextExtractionException.Reason.INPUT_TOO_LARGE)
        for (var owner : batch) process(java.util.List.of(owner), false);
      else for (var owner : batch) failed(owner, error);
    } catch (RuntimeException error) {
      for (var owner : batch) failed(owner, error);
    }
  }

  private void failed(AgentRunContext owner, RuntimeException error) {
    var diagnostic = dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.classify(error);
    publish(
        owner,
        AgentEventType.WORK_CONTEXT_UPDATE_FAILED,
        null,
        diagnostic.display()
            + ("timeout".equals(diagnostic.code()) ? " 設定: rei.work-context.timeout-seconds。" : "")
            + " 元 Run の結果には影響しません。/work show で保存状態を確認し、必要なら /work update を実行してください。");
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
    synchronized (queueLock) {
      closed = true;
    }
    worker.shutdown();
    try {
      if (!worker.awaitTermination(2, TimeUnit.SECONDS)) worker.shutdownNow();
    } catch (InterruptedException error) {
      worker.shutdownNow();
      Thread.currentThread().interrupt();
    }
    java.util.List<AgentRunContext> abandoned;
    synchronized (queueLock) {
      abandoned = pending.values().stream().flatMap(p -> p.values().stream()).toList();
      pending.clear();
      queued = 0;
    }
    for (var owner : abandoned)
      failed(owner, new RejectedExecutionException("Work Context worker closed"));
  }
}
