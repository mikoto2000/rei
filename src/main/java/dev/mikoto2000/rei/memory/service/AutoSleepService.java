package dev.mikoto2000.rei.memory.service;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.memory.configuration.AutoSleepProperties;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.topic.AgentActivityTracker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One bounded idle batch at a time; persistent Sleep cursors remain the source of truth. */
@Component
@EnableConfigurationProperties(AutoSleepProperties.class)
public class AutoSleepService implements AutoCloseable {
  private final SleepService sleep;
  private final MemoryProperties memory;
  private final AutoSleepProperties properties;
  private final AgentActivityTracker activity;
  private final Clock clock;
  private final Map<String, String> sessions = new LinkedHashMap<>();
  private final Map<String, Instant> attempts = new HashMap<>();
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            var thread = new Thread(r, "rei-auto-sleep");
            thread.setDaemon(true);
            return thread;
          });
  private volatile Thread runningThread;
  private volatile String runningSession;

  private record Pending(
      MemoryRepository.AutoState state, String code, String description, String timestamp) {}

  private final Map<String, Pending> pendingUpdates = new ConcurrentHashMap<>();
  private MemoryEvents events;

  @org.springframework.beans.factory.annotation.Autowired
  public void setEvents(MemoryEvents events) {
    this.events = events;
  }

  private final java.util.concurrent.atomic.AtomicBoolean executing =
      new java.util.concurrent.atomic.AtomicBoolean();
  private dev.mikoto2000.rei.application.session.SessionRepository savedSessions;
  private dev.mikoto2000.rei.core.project.ProjectService projects;
  private Iterator<dev.mikoto2000.rei.application.session.SessionMetadata> startup;
  private boolean startupCaptured;
  private Instant nextMetadataScan;
  private dev.mikoto2000.rei.application.session.CursorKey metadataCursor;
  private boolean metadataScanning;

  @org.springframework.beans.factory.annotation.Autowired
  public synchronized void setStartupSources(
      dev.mikoto2000.rei.application.session.SessionRepository savedSessions,
      dev.mikoto2000.rei.core.project.ProjectService projects) {
    this.savedSessions = savedSessions;
    this.projects = projects;
  }

  /** Metadata only: no history reads or model calls until the usual idle gates pass. */
  private void discoverStartup() {
    if (savedSessions == null || projects == null) return;
    if (!startupCaptured) {
      startup = savedSessions.completionSnapshot().iterator();
      startupCaptured = true;
    }
    var registered = projects.completionProjects();
    for (int scanned = 0; scanned < 256 && sessions.size() < 256 && startup.hasNext(); scanned++) {
      var item = startup.next();
      registerMetadata(item, registered);
    }
    if (!startup.hasNext()) startup = Collections.emptyIterator();
  }

  private void registerMetadata(
      dev.mikoto2000.rei.application.session.SessionMetadata item,
      List<dev.mikoto2000.rei.core.project.ProjectContext> registered) {
    if (item.sessionId().isBlank()
        || item.projectId().isBlank()
        || registered.stream().noneMatch(p -> p.id().equals(item.projectId()))) return;
    try {
      String encoded = dev.mikoto2000.rei.core.project.ProjectStorage.projectId(item.sessionId());
      if (encoded != null && !encoded.equals(item.projectId())) return;
      sessions.putIfAbsent(item.sessionId(), item.projectId());
    } catch (IllegalArgumentException invalidIdentity) {
      /* Unusable metadata is never a Sleep candidate. */
    }
  }

  /**
   * Periodic persistent reads catch metadata added after startup, without an unbounded candidate
   * queue.
   */
  private void discoverSaved(Instant now) {
    discoverStartup();
    if (!startupCaptured || startup.hasNext() || sessions.size() >= 256) return;
    if (!metadataScanning) {
      if (nextMetadataScan == null) {
        nextMetadataScan = now.plus(properties.retryInterval());
        return;
      }
      if (now.isBefore(nextMetadataScan)) return;
      metadataScanning = true;
      metadataCursor = null;
    }
    try {
      int limit = Math.min(100, 256 - sessions.size());
      var rows = savedSessions.findPage(null, metadataCursor, limit);
      if (rows.size() > limit)
        throw new IllegalStateException("Session metadata page exceeds limit");
      var registered = projects.completionProjects();
      for (var item : rows) registerMetadata(item, registered);
      if (rows.size() < limit) {
        metadataScanning = false;
        metadataCursor = null;
        nextMetadataScan = now.plus(properties.retryInterval());
      } else {
        var last = rows.getLast();
        var next =
            new dev.mikoto2000.rei.application.session.CursorKey(
                last.updatedAt(), last.sessionId());
        if (next.equals(metadataCursor))
          throw new IllegalStateException("Session metadata cursor did not advance");
        metadataCursor = next;
      }
    } catch (RuntimeException error) {
      metadataScanning = false;
      metadataCursor = null;
      nextMetadataScan = now.plus(properties.retryInterval());
      throw error;
    }
  }

  private final org.springframework.scheduling.support.CronExpression cron;
  private Instant nextCron;
  private long runningVersion;
  private volatile boolean closed;

  public AutoSleepService(
      SleepService sleep,
      MemoryProperties memory,
      AutoSleepProperties properties,
      AgentActivityTracker activity,
      Clock clock) {
    this.sleep = sleep;
    this.memory = memory;
    this.properties = properties;
    this.activity = activity;
    this.clock = clock;
    if (Duration.ofSeconds(memory.sleep().timeoutSeconds()).compareTo(properties.maxTimeout()) > 0)
      throw new IllegalArgumentException(
          "Auto Sleep max-timeout must cover the base Sleep timeout");
    cron =
        properties.cron() == null
            ? null
            : org.springframework.scheduling.support.CronExpression.parse(properties.cron());
    if (cron != null) advanceCron(clock.instant());
  }

  /** Called only after durable terminal turn metadata. Never invokes the LLM on the user thread. */
  public synchronized void afterTerminal(AgentRunContext owner) {
    if (closed || !properties.enabled() || !memory.enabled() || owner.projectId() == null) return;
    sessions.put(owner.conversationId(), owner.projectId());
    while (sessions.size() > 256) {
      String oldest = sessions.keySet().iterator().next();
      sessions.remove(oldest);
      attempts.remove(oldest);
    }
  }

  /** Explicit end never starts a model or destroys resumable conversation history. */
  public synchronized void afterSessionEnd(
      dev.mikoto2000.rei.application.session.SessionMetadata session) {
    if (closed || !properties.enabled() || !properties.onSessionEnd() || !memory.enabled()) return;
    sleep.requestAutoSleep(session.sessionId(), session.projectId(), "session_end");
    sessions.put(session.sessionId(), session.projectId());
    while (sessions.size() > 256) {
      String oldest = sessions.keySet().iterator().next();
      sessions.remove(oldest);
      attempts.remove(oldest);
    }
  }

  private Map<String, MemoryRepository.AutoSleepRequest> discoverEndRequests() {
    if (projects == null || (!properties.onSessionEnd() && !properties.onShutdown()))
      return Map.of();
    var registered = projects.completionProjects();
    var requested = new LinkedHashMap<String, MemoryRepository.AutoSleepRequest>();
    for (var request : sleep.pendingAutoSleepRequests()) {
      if (!(request.cause().equals("session_end") && properties.onSessionEnd())
          && !(request.cause().equals("shutdown") && properties.onShutdown())) continue;
      if (registered.stream().noneMatch(p -> p.id().equals(request.projectId()))) continue;
      if (sleep.autoState(request.sessionId(), request.projectId()).stoppedAt() != null) continue;
      try {
        String encoded =
            dev.mikoto2000.rei.core.project.ProjectStorage.projectId(request.sessionId());
        if (encoded != null && !encoded.equals(request.projectId())) continue;
        if (sessions.containsKey(request.sessionId())
            && !sessions.get(request.sessionId()).equals(request.projectId())) continue;
        requested.put(request.sessionId(), request);
      } catch (IllegalArgumentException invalidIdentity) {
        /* A persisted request cannot override ownership. */
      }
    }
    var ordered = new LinkedHashMap<String, String>();
    requested.values().forEach(r -> ordered.put(r.sessionId(), r.projectId()));
    for (var entry : sessions.entrySet())
      if (ordered.size() < 256) ordered.putIfAbsent(entry.getKey(), entry.getValue());
    sessions.clear();
    sessions.putAll(ordered);
    return requested;
  }

  @Scheduled(fixedDelayString = "${rei.memory.auto-sleep.check-interval:5s}")
  public synchronized void tick() {
    if (closed) return;
    if (executing.get()) {
      Thread thread = runningThread;
      if (thread != null
          && (activity.isAgentBusy() || activity.activityVersion() != runningVersion))
        thread.interrupt();
      return;
    }
    if (!properties.enabled() || !memory.enabled() || activity.isAgentBusy()) return;
    long version = activity.activityVersion();
    Instant now = clock.instant();
    Instant latest =
        java.util.stream.Stream.of(
                activity.applicationStartedAt(),
                activity.lastUserActivityAt(),
                activity.lastAgentActivityAt())
            .filter(Objects::nonNull)
            .max(Instant::compareTo)
            .orElse(now);
    if (Duration.between(latest, now).compareTo(properties.minimumIdle()) < 0) return;
    Map<String, MemoryRepository.AutoSleepRequest> requested;
    try {
      requested = discoverEndRequests();
    } catch (RuntimeException error) {
      requested = Map.of();
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Auto Sleep requests unavailable ({})", error.getClass().getSimpleName());
    }
    if (requested.isEmpty() && cron != null && (nextCron == null || now.isBefore(nextCron))) return;
    // Coalesce missed occurrences into one idle opportunity; never replay a backlog.
    if (cron != null && nextCron != null && !now.isBefore(nextCron)) advanceCron(now);
    try {
      discoverSaved(now);
    } catch (RuntimeException error) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Auto Sleep discovery unavailable ({})", error.getClass().getSimpleName());
    }
    var candidates = sessions.entrySet().iterator();
    while (candidates.hasNext()) {
      var session = candidates.next();
      Instant previous = attempts.get(session.getKey());
      if (previous != null && now.isBefore(previous.plus(properties.retryInterval()))) continue;
      try {
        var pending = pendingUpdates.get(session.getKey());
        if (pending != null) {
          var current = sleep.autoState(session.getKey(), session.getValue());
          if (current.revision() == pending.state().revision()) {
            var updated =
                sleep.updateAutoState(
                    pending.state(),
                    pending.code(),
                    pending.description(),
                    pending.timestamp(),
                    properties.failureLimit());
            notifyStopped(pending.state(), updated);
          } else {
            org.slf4j.LoggerFactory.getLogger(getClass())
                .warn(
                    "Auto Sleep stale result discarded session={} revision={}",
                    session.getKey(),
                    pending.state().revision());
          }
          pendingUpdates.remove(session.getKey());
        }
        var state = sleep.autoState(session.getKey(), session.getValue());
        if (state.stoppedAt() != null) {
          candidates.remove();
          continue;
        }
        if (SleepService.isRunning(session.getKey())) continue;
        var request = requested.get(session.getKey());
        if (sleep.unsleptTurns(session.getKey())
            < (request == null ? properties.minimumTurns() : 1)) {
          if (request != null) sleep.completeAutoSleepRequest(request);
          attempts.remove(session.getKey());
          candidates.remove();
          continue;
        }
        String id = session.getKey(), project = session.getValue();
        runningVersion = version;
        attempts.put(id, now);
        executing.set(true);
        runningSession = id;
        worker.submit(
            () -> {
              runningThread = Thread.currentThread();
              try {
                var timeout = timeout(state.stage());
                org.slf4j.LoggerFactory.getLogger(getClass())
                    .info(
                        "Auto Sleep session={} wait={} total={}",
                        id,
                        timeout,
                        properties.totalTimeout());
                var execution = new AutoSleepExecution(timeout, properties.totalTimeout());
                var report =
                    sleep.sleep(
                        id,
                        project,
                        false,
                        () ->
                            closed
                                || activity.isAgentBusy()
                                || activity.activityVersion() != version,
                        execution);
                if (report.run().processedTurns() > 0 || execution.hasSavedProgress()) {
                  try {
                    sleep.updateAutoState(
                        state, null, null, clock.instant().toString(), properties.failureLimit());
                  } catch (RuntimeException update) {
                    pendingUpdates.put(
                        id, new Pending(state, null, null, clock.instant().toString()));
                    warnState(id, update);
                  }
                }
                if (request != null)
                  try {
                    if (sleep.unsleptTurns(id) == 0) sleep.completeAutoSleepRequest(request);
                  } catch (RuntimeException update) {
                    warnState(id, update);
                  }
              } catch (RuntimeException error) {
                if (!dev.mikoto2000.rei.core.chat.RunCancellation.isCancellation(error)
                    && !Thread.currentThread().isInterrupted()
                    && !closed
                    && !activity.isAgentBusy()
                    && activity.activityVersion() == version
                    && !(error instanceof SleepService.SleepBusyException)
                    && !(error
                        instanceof dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException)) {
                  String code = classify(error),
                      description = description(code, state.stage()),
                      failedAt = clock.instant().toString();
                  // Log types and frames, never provider messages or response/request bodies.
                  var logger = org.slf4j.LoggerFactory.getLogger(getClass());
                  for (Throwable cause = error; cause != null; cause = cause.getCause())
                    logger.warn(
                        "Auto Sleep session={} code={} exception={} frames={}",
                        id,
                        code,
                        cause.getClass().getName(),
                        Arrays.toString(
                            Arrays.copyOf(
                                cause.getStackTrace(),
                                Math.min(12, cause.getStackTrace().length))));
                  try {
                    var updated =
                        sleep.updateAutoState(
                            state, code, description, failedAt, properties.failureLimit());
                    notifyStopped(state, updated);
                  } catch (RuntimeException update) {
                    pendingUpdates.put(id, new Pending(state, code, description, failedAt));
                    warnState(id, update);
                  }
                }
              } finally {
                runningThread = null;
                runningSession = null;
                executing.set(false);
              }
            });
        return;
      } catch (RuntimeException error) {
        executing.set(false);
        attempts.put(session.getKey(), now);
        warnState(session.getKey(), error);
      }
    }
  }

  private void notifyStopped(MemoryRepository.AutoState before, MemoryRepository.AutoState after) {
    if (before.stoppedAt() == null && after.stoppedAt() != null && events != null)
      events.publish(
          dev.mikoto2000.rei.event.AgentEventType.AUTO_SLEEP_STOPPED,
          after.project(),
          after.session(),
          null,
          false,
          0,
          0,
          0,
          "[memory] Auto Sleepを停止しました\nSession: "
              + after.session()
              + "\n連続失敗: "
              + after.failures()
              + "回\n理由: "
              + after.description()
              + "\n未整理の会話は保持しています。\n再開: /sleep auto resume");
  }

  private void warnState(String session, RuntimeException error) {
    org.slf4j.LoggerFactory.getLogger(getClass())
        .warn("Auto Sleep state unavailable session={} ({})", session, error.getClass().getName());
  }

  public Duration timeout(int stage) {
    long multiplier =
        Math.min(properties.maxTimeoutMultiplier(), 1L << Math.min(30, Math.max(0, stage)));
    long seconds = Math.multiplyExact((long) memory.sleep().timeoutSeconds(), multiplier);
    var value = Duration.ofSeconds(seconds);
    return value.compareTo(properties.maxTimeout()) > 0 ? properties.maxTimeout() : value;
  }

  static String classify(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof AutoSleepExecution.Failure failure) return failure.code;
      if (cause instanceof dev.mikoto2000.rei.subagent.SubAgentValidationException)
        return "OUTPUT_INVALID";
      if (cause instanceof org.springframework.dao.DataAccessException
          || cause instanceof org.springframework.transaction.TransactionException)
        return "PERSISTENCE_FAILED";
    }
    return error instanceof IllegalArgumentException ? "OUTPUT_INVALID" : "UNKNOWN";
  }

  private String description(String code, int stage) {
    return switch (code) {
      case "MODEL_TIMEOUT" -> "モデル応答の完了待ちが" + timeout(stage).toSeconds() + "秒でタイムアウト";
      case "AUTO_SLEEP_DEADLINE_EXCEEDED" -> "自動Sleep全体の時間上限を超過";
      case "MODEL_REQUEST_FAILED" -> "モデル要求に失敗";
      case "OUTPUT_INVALID" -> "出力検証に失敗";
      case "PERSISTENCE_FAILED" -> "結果の保存に失敗";
      default -> "原因未分類の失敗";
    };
  }

  public boolean isExecuting(String session) {
    return session != null && session.equals(runningSession);
  }

  public synchronized String status(String session, String project) {
    var state = sleep.autoState(session, project);
    return "Auto Sleep: "
        + (!properties.enabled() || !memory.enabled()
            ? "無効"
            : state.stoppedAt() != null ? "停止" : "有効")
        + "\n実行中: "
        + isExecuting(session)
        + "\nSession: "
        + session
        + "\n連続失敗: "
        + state.failures()
        + "回\n停止日時: "
        + state.stoppedAt()
        + "\n直近理由: "
        + state.code()
        + " / "
        + state.description()
        + "\n直近失敗日時: "
        + state.lastFailureAt()
        + "\nタイムアウト段階: "
        + state.stage()
        + " / 適用時間: "
        + timeout(state.stage()).toSeconds()
        + "秒"
        + "\n全体時間上限: "
        + properties.totalTimeout().toSeconds()
        + "秒";
  }

  public synchronized String resume(String session, String project) {
    if (session.equals(runningSession) || SleepService.isRunning(session))
      throw new IllegalStateException("当該SessionのSleep実行中は再開できません");
    var state = sleep.autoState(session, project);
    sleep.updateAutoState(state, null, null, clock.instant().toString(), properties.failureLimit());
    pendingUpdates.remove(session);
    sessions.put(session, project);
    return "Auto Sleepの停止状態・失敗回数・段階をリセットしました。"
        + (!properties.enabled() || !memory.enabled()
            ? "自動機能は無効です。"
            : "次回は既存のアイドル・量・予算・試行間隔に従います。");
  }

  private void advanceCron(Instant after) {
    var next = cron.next(after.atZone(ZoneId.of(properties.zone())));
    nextCron = next == null ? null : next.toInstant();
  }

  @jakarta.annotation.PreDestroy
  @Override
  public void close() {
    synchronized (this) {
      if (closed) return;
      closed = true;
      if (properties.enabled() && properties.onShutdown() && memory.enabled()) {
        try {
          discoverStartup();
        } catch (RuntimeException error) {
          org.slf4j.LoggerFactory.getLogger(getClass())
              .warn(
                  "Auto Sleep shutdown discovery unavailable ({})",
                  error.getClass().getSimpleName());
        }
        for (var entry : sessions.entrySet())
          try {
            sleep.requestAutoSleep(entry.getKey(), entry.getValue(), "shutdown");
          } catch (RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                .warn(
                    "Auto Sleep shutdown request unavailable ({})",
                    error.getClass().getSimpleName());
          }
      }
      worker.shutdown();
    }
    try {
      if (!worker.awaitTermination(2, TimeUnit.SECONDS)) worker.shutdownNow();
    } catch (InterruptedException error) {
      worker.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
