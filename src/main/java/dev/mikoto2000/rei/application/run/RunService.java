package dev.mikoto2000.rei.application.run;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.ErrorInformation;
import java.util.function.Predicate;

/** 実行のユースケースを実現する。 */
public class RunService implements AutoCloseable {
  /** 登録台帳。 */
  private final RunRegistry registry;

  /** 通知の連携先。 */
  private final AgentEventBus bus;

  /** イベント一覧。 */
  private final AgentEventFactory events;

  /** キャンセル。 */
  private final CommandCancellationService cancellation;

  /** キャンセル待機中。 */
  private final Predicate<String> cancelQueued;

  /** 終了通知が先に届いても、runner の finally が戻るまで終端状態を公開しない。 */
  private final java.util.Set<String> executing = new java.util.HashSet<>();

  private final java.util.Map<String, AgentEvent> terminalEvents = new java.util.HashMap<>();

  /** 購読。 */
  private final AgentEventBus.Subscription subscription;

  /** 待機中キャンセル。 */
  private final java.util.Map<String, Runnable> queuedCancellation = new java.util.HashMap<>();

  /** 通知に応じて状態や表示を更新するため、待機中キャンセルを受け付ける。 */
  public void onQueuedCancellation(String runId, Runnable cancelled) {
    runAtomically(
        () -> {
          if (registry.get(runId).status() != RunStatus.QUEUED
              || queuedCancellation.putIfAbsent(runId, java.util.Objects.requireNonNull(cancelled))
                  != null)
            throw new IllegalStateException(
                "Queued cancellation already registered or Run started");
        });
  }

  /** 実行の受付と状態管理に関する記憶の削除待機中キャンセルを処理する。 */
  public void forgetQueuedCancellation(String runId) {
    runAtomically(
        () -> {
          queuedCancellation.remove(runId);
        });
  }

  /** 実行の受付と状態管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public RunService(RunRegistry registry) {
    this(registry, null, null, null, null);
  }

  /** 指定された値や利用先を使って、RunServiceを初期化する。 */
  public RunService(
      RunRegistry registry,
      AgentEventBus bus,
      AgentEventFactory events,
      CommandCancellationService cancellation,
      Predicate<String> cancelQueued) {
    this.registry = registry;
    this.bus = bus;
    this.events = events;
    this.cancellation = cancellation;
    this.cancelQueued = cancelQueued;
    subscription = bus == null ? null : bus.subscribe(this::onEvent);
  }

  /** 実行の受付と状態管理で用いる排他制御を返す。 */
  private <T> T atomically(java.util.function.Supplier<T> action) {
    if (bus != null) return bus.withReplaySnapshot(action);
    synchronized (registry) {
      return action.get();
    }
  }

  private void runAtomically(Runnable action) {
    atomically(
        () -> {
          action.run();
          return null;
        });
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public RunSnapshot get(String runId) {
    return atomically(
        () -> {
          purgeExpired();
          return registry.get(runId);
        });
  }

  /** Per-frame lookup without scanning unrelated runs or their replay histories. */
  public RunStatus streamStatus(String runId) {
    return atomically(
        () -> {
          return registry.streamStatus(runId);
        });
  }

  /** 実行の受付と状態管理において、復元済みに該当するか判定する。 */
  public boolean restored(String runId) {
    return atomically(
        () -> {
          return registry.restored(runId);
        });
  }

  /** 実行の受付と状態管理に関する保持期限を過ぎた情報の削除期限切れを処理する。 */
  public void purgeExpired() {
    runAtomically(
        () -> {
          var expired = registry.purgeExpired();
          expired.forEach(queuedCancellation::remove);
          if (bus != null) {
            expired.forEach(bus::purgeRun);
            bus.purgeExpired(registry.runIds());
          }
        });
  }

  /**
   * 実行の受付と状態管理で受け渡すキャンセル結果を表す。
   *
   * @param accepted 受付済み
   * @param run 実行
   */
  public record CancellationResult(boolean accepted, RunSnapshot run) {}

  /**
   * 利用者が不要とした実行を止めるため、待機中限定をキャンセルする。
   *
   * <p>Pending input cancellation must never turn into cancellation of already executing work.
   */
  public CancellationResult cancelQueuedOnly(String runId) {
    var notify = new java.util.concurrent.atomic.AtomicReference<Runnable>();
    CancellationResult result;
    result =
        atomically(
            () -> {
              purgeExpired();
              var current = registry.get(runId);
              if (current.status() != RunStatus.QUEUED || !cancelQueued.test(runId))
                return new CancellationResult(false, current);
              registry.transition(runId, RunStatus.CANCELLED, null);
              cancellation.forgetPendingCancellation(runId);
              notify.set(queuedCancellation.remove(runId));
              bus.publish(
                  events
                      .runCancelled(
                          runId,
                          new ErrorInformation(
                              "CancellationException",
                              "ユーザーの要求により待機中の依頼を取り消しました。",
                              "user_cancelled"))
                      .withOwnership(current.context()));
              return new CancellationResult(true, registry.get(runId));
            });
    notifyCancellation(notify.get());
    return result;
  }

  /** 対象の実行をキャンセルする。 */
  public CancellationResult cancel(String runId) {
    var notify = new java.util.concurrent.atomic.AtomicReference<Runnable>();
    var request =
        new java.util.concurrent.atomic.AtomicReference<
            CommandCancellationService.CancellationRequest>();
    CancellationResult result;
    result =
        atomically(
            () -> {
              CancellationResult outcome;
              purgeExpired();
              var current = registry.get(runId);
              if (current.status().isWaiting() && !registry.claimWaiting(current.context()))
                return new CancellationResult(false, current);
              boolean queuedRemoved =
                  current.status() == RunStatus.QUEUED && cancelQueued.test(runId);
              if (!current.status().isTerminal()
                  && (current.status() == RunStatus.RUNNING
                      || current.status() == RunStatus.QUEUED && !queuedRemoved
                      || executing.contains(runId))) {
                request.set(cancellation.requestRun(runId));
                outcome = new CancellationResult(request.get().accepted(), registry.get(runId));
              } else {
                if (!registry.transition(runId, RunStatus.CANCELLED, null) && !queuedRemoved)
                  return new CancellationResult(false, current);
                if (current.status() == RunStatus.QUEUED) {
                  cancellation.forgetPendingCancellation(runId);
                  notify.set(queuedCancellation.remove(runId));
                } else request.set(cancellation.requestRun(runId));
                bus.publish(
                    events
                        .runCancelled(
                            runId,
                            new ErrorInformation(
                                "CancellationException", "ユーザーの要求により実行を停止しました。", "user_cancelled"))
                        .withOwnership(current.context()));
                outcome = new CancellationResult(true, registry.get(runId));
              }

              return outcome;
            });
    // 子の停止や dispose はブロックやイベント発行を伴い得る。共有 monitor を保持しない。
    if (request.get() != null) request.get().dispatch().run();
    notifyCancellation(notify.get());
    return result;
  }

  /** 状態の変化を利用先へ伝えるため、キャンセルを通知する。 */
  private void notifyCancellation(Runnable notify) {
    if (notify != null)
      try {
        notify.run();
      } catch (RuntimeException error) {
        org.slf4j.LoggerFactory.getLogger(RunService.class)
            .warn("Queued cancellation notification failed: {}", error.getClass().getSimpleName());
      }
  }

  /**
   * 要求された処理を実行する。
   *
   * <p>Invoked inside the project executor, and returns only after runner cleanup.
   */
  public void execute(AgentRunContext context, Runnable runner) {
    var skipped = new java.util.concurrent.atomic.AtomicReference<Runnable>();
    boolean started;
    started =
        atomically(
            () -> {
              skipped.set(queuedCancellation.remove(context.runId()));
              boolean stopped = cancellation.diagnostic(context.runId()).requestedAt() != null;
              if (stopped && registry.transition(context.runId(), RunStatus.CANCELLED, null))
                bus.publish(events.runCancelled(context.runId(), null).withOwnership(context));
              boolean admitted =
                  !stopped && registry.transition(context.runId(), RunStatus.RUNNING, null);
              if (admitted) {
                executing.add(context.runId());
                registry.beginExecution(context.runId());
              }
              if (!admitted) {
                cancellation.forgetPendingCancellation(context.runId());
              }

              return admitted;
            });
    if (!started) {
      notifyCancellation(skipped.get());
      return;
    }
    String failure = "Runner ended without a terminal event";
    try {
      runner.run();
    } catch (RuntimeException error) {
      failure = "Runner failed: " + dev.mikoto2000.rei.event.FailureMessages.describe(error);
    } finally {
      try {
        cancellation.completeResources(context.runId());
        String completedFailure = failure;
        runAtomically(
            () -> {
              executing.remove(context.runId());
              var terminal = terminalEvents.remove(context.runId());
              var stop = cancellation.diagnostic(context.runId());
              if (stop.requestedAt() != null) {
                if (registry.transition(context.runId(), RunStatus.CANCELLED, null))
                  bus.publish(
                      events
                          .runCancelled(
                              context.runId(),
                              new ErrorInformation(
                                  "CancellationException",
                                  "ユーザーの停止要求後、実行と後片付けが終了しました。",
                                  "user_cancelled"))
                          .withOwnership(context));
              } else if (stop.failure() != null) {
                fail(context, "Run resource cleanup failed: " + stop.failure());
              } else {
                if (terminal != null) onEvent(terminal);
                if (!registry.get(context.runId()).status().isWaiting())
                  fail(context, completedFailure);
              }
              cancellation.forgetPendingCancellation(context.runId());
            });
      } finally {
        registry.endExecution(context.runId());
      }
    }
  }

  /** 停止要求と終了確認の診断情報を返す。 */
  public CommandCancellationService.Diagnostic cancellationDiagnostic(String runId) {
    return cancellation == null ? null : cancellation.diagnostic(runId);
  }

  /** 実行の受付と状態管理に関するfailを処理する。 */
  private void fail(AgentRunContext context, String message) {
    runAtomically(
        () -> {
          var failure = new RunFailure("ExecutionFailure", message);
          if (registry.transition(context.runId(), RunStatus.FAILED, failure))
            bus.publish(
                events
                    .runFailed(
                        context.runId(),
                        new ErrorInformation(failure.type(), failure.message(), null))
                    .withOwnership(context));
        });
  }

  /**
   * 終了時の状態を確定するため、未指定終端を完了させる。
   *
   * <p>For application shortcuts that never entered Chat; transition and event publication are
   * atomic with cancel.
   */
  public void finishMissingTerminal(AgentRunContext context, RunStatus terminal) {
    // 終了済みの実行について、現在の状態に応じた扱いを選ぶ。
    if (!terminal.isTerminal()) throw new IllegalArgumentException("Terminal state required");
    cancellation.completeResources(context.runId());
    runAtomically(
        () -> {
          var effectiveTerminal =
              cancellation.diagnostic(context.runId()).requestedAt() != null
                  ? RunStatus.CANCELLED
                  : terminal;
          var failure =
              effectiveTerminal == RunStatus.UNKNOWN
                  ? new RunFailure(
                      "UnknownOutcome",
                      "Application Run outcome unknown; inspect saved child checkpoint")
                  : effectiveTerminal == RunStatus.FAILED
                      ? new RunFailure("ExecutionFailure", "Application Run failed")
                      : null;
          if (registry.transition(context.runId(), effectiveTerminal, failure)) {
            var event =
                switch (effectiveTerminal) {
                  case COMPLETED -> events.runCompleted(context.runId(), 0);
                  case CANCELLED -> events.runCancelled(context.runId(), null);
                  default ->
                      events.runFailed(
                          context.runId(),
                          new ErrorInformation(failure.type(), failure.message(), null));
                };
            bus.publish(event.withOwnership(context));
          }
        });
  }

  /** 通知に応じて状態や表示を更新するため、イベントを受け付ける。 */
  private void onEvent(AgentEvent event) {
    if (event.runId() == null) return;
    RunStatus status =
        switch (event.type()) {
          case AGENT_RUN_COMPLETED -> RunStatus.COMPLETED;
          case AGENT_RUN_FAILED -> RunStatus.FAILED;
          case AGENT_RUN_CANCELLED -> RunStatus.CANCELLED;
          default -> null;
        };
    if (status == null) return;
    runAtomically(
        () -> {
          if (executing.contains(event.runId())) {
            terminalEvents.putIfAbsent(event.runId(), event);
            return;
          }
          try {
            // Do not expose arbitrary exception messages (which may contain credentials) through
            // status
            // polling.
            registry.transition(
                event.runId(),
                status,
                status == RunStatus.FAILED
                    ? new RunFailure("ExecutionFailure", "Agent run failed")
                    : null);
          } catch (RunNotFoundException ignored) {
            /* CLI and auxiliary runs need no Web registry entry. */
          }
        });
  }

  /** 実行の受付と状態管理で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  public void close() {
    if (subscription != null) subscription.unsubscribe();
    runAtomically(
        () -> {
          queuedCancellation.clear();
          terminalEvents.clear();
          executing.clear();
        });
  }
}
