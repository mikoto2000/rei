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
    synchronized (monitor()) {
      if (registry.get(runId).status() != RunStatus.QUEUED
          || queuedCancellation.putIfAbsent(runId, java.util.Objects.requireNonNull(cancelled))
              != null)
        throw new IllegalStateException("Queued cancellation already registered or Run started");
    }
  }

  /** 実行の受付と状態管理に関する記憶の削除待機中キャンセルを処理する。 */
  public void forgetQueuedCancellation(String runId) {
    synchronized (monitor()) {
      queuedCancellation.remove(runId);
    }
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
  private Object monitor() {
    return bus == null ? registry : bus;
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public RunSnapshot get(String runId) {
    synchronized (monitor()) {
      purgeExpired();
      return registry.get(runId);
    }
  }

  /** 実行の受付と状態管理において、復元済みに該当するか判定する。 */
  public boolean restored(String runId) {
    synchronized (monitor()) {
      return registry.restored(runId);
    }
  }

  /** 実行の受付と状態管理に関する保持期限を過ぎた情報の削除期限切れを処理する。 */
  public void purgeExpired() {
    synchronized (monitor()) {
      var expired = registry.purgeExpired();
      expired.forEach(queuedCancellation::remove);
      if (bus != null) {
        expired.forEach(bus::purgeRun);
        bus.purgeExpired(registry.runIds());
      }
    }
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
    Runnable notify;
    CancellationResult result;
    synchronized (monitor()) {
      purgeExpired();
      var current = registry.get(runId);
      if (current.status() != RunStatus.QUEUED || !cancelQueued.test(runId))
        return new CancellationResult(false, current);
      registry.transition(runId, RunStatus.CANCELLED, null);
      cancellation.forgetPendingCancellation(runId);
      notify = queuedCancellation.remove(runId);
      bus.publish(
          events
              .runCancelled(
                  runId,
                  new ErrorInformation(
                      "CancellationException", "ユーザーの要求により待機中の依頼を取り消しました。", "user_cancelled"))
              .withOwnership(current.context()));
      result = new CancellationResult(true, registry.get(runId));
    }
    notifyCancellation(notify);
    return result;
  }

  /** 対象の実行をキャンセルする。 */
  public CancellationResult cancel(String runId) {
    Runnable notify = null;
    CommandCancellationService.CancellationRequest request = null;
    CancellationResult result;
    synchronized (monitor()) {
      purgeExpired();
      var current = registry.get(runId);
      if (current.status().isWaiting() && !registry.claimWaiting(current.context()))
        return new CancellationResult(false, current);
      boolean queuedRemoved = current.status() == RunStatus.QUEUED && cancelQueued.test(runId);
      if (!current.status().isTerminal()
          && (current.status() == RunStatus.RUNNING
              || current.status() == RunStatus.QUEUED && !queuedRemoved
              || executing.contains(runId))) {
        request = cancellation.requestRun(runId);
        result = new CancellationResult(request.accepted(), registry.get(runId));
      } else {
        if (!registry.transition(runId, RunStatus.CANCELLED, null) && !queuedRemoved)
          return new CancellationResult(false, current);
        if (current.status() == RunStatus.QUEUED) {
          cancellation.forgetPendingCancellation(runId);
          notify = queuedCancellation.remove(runId);
        } else request = cancellation.requestRun(runId);
        bus.publish(
            events
                .runCancelled(
                    runId,
                    new ErrorInformation(
                        "CancellationException", "ユーザーの要求により実行を停止しました。", "user_cancelled"))
                .withOwnership(current.context()));
        result = new CancellationResult(true, registry.get(runId));
      }
    }
    // 子の停止や dispose はブロックやイベント発行を伴い得る。共有 monitor を保持しない。
    if (request != null) request.dispatch().run();
    notifyCancellation(notify);
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
    Runnable skipped;
    boolean started;
    synchronized (monitor()) {
      skipped = queuedCancellation.remove(context.runId());
      boolean stopped = cancellation.diagnostic(context.runId()).requestedAt() != null;
      if (stopped && registry.transition(context.runId(), RunStatus.CANCELLED, null))
        bus.publish(events.runCancelled(context.runId(), null).withOwnership(context));
      started = !stopped && registry.transition(context.runId(), RunStatus.RUNNING, null);
      if (started) executing.add(context.runId());
      if (!started) {
        cancellation.forgetPendingCancellation(context.runId());
      }
    }
    if (!started) {
      notifyCancellation(skipped);
      return;
    }
    String failure = "Runner ended without a terminal event";
    try {
      runner.run();
    } catch (RuntimeException error) {
      failure = "Runner failed: " + dev.mikoto2000.rei.event.FailureMessages.describe(error);
    } finally {
      cancellation.completeResources(context.runId());
      synchronized (monitor()) {
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
          if (!registry.get(context.runId()).status().isWaiting()) fail(context, failure);
        }
        cancellation.forgetPendingCancellation(context.runId());
      }
    }
  }

  /** 停止要求と終了確認の診断情報を返す。 */
  public CommandCancellationService.Diagnostic cancellationDiagnostic(String runId) {
    return cancellation == null ? null : cancellation.diagnostic(runId);
  }

  /** 実行の受付と状態管理に関するfailを処理する。 */
  private void fail(AgentRunContext context, String message) {
    synchronized (monitor()) {
      var failure = new RunFailure("ExecutionFailure", message);
      if (registry.transition(context.runId(), RunStatus.FAILED, failure))
        bus.publish(
            events
                .runFailed(
                    context.runId(), new ErrorInformation(failure.type(), failure.message(), null))
                .withOwnership(context));
    }
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
    cancellation.awaitChildren(context.runId());
    synchronized (monitor()) {
      if (cancellation.diagnostic(context.runId()).requestedAt() != null)
        terminal = RunStatus.CANCELLED;
      var failure =
          terminal == RunStatus.UNKNOWN
              ? new RunFailure(
                  "UnknownOutcome",
                  "Application Run outcome unknown; inspect saved child checkpoint")
              : terminal == RunStatus.FAILED
                  ? new RunFailure("ExecutionFailure", "Application Run failed")
                  : null;
      if (registry.transition(context.runId(), terminal, failure)) {
        var event =
            switch (terminal) {
              case COMPLETED -> events.runCompleted(context.runId(), 0);
              case CANCELLED -> events.runCancelled(context.runId(), null);
              default ->
                  events.runFailed(
                      context.runId(),
                      new ErrorInformation(failure.type(), failure.message(), null));
            };
        bus.publish(event.withOwnership(context));
      }
    }
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
    synchronized (monitor()) {
      if (executing.contains(event.runId())) {
        terminalEvents.putIfAbsent(event.runId(), event);
        return;
      }
      try {
        // Do not expose arbitrary exception messages (which may contain credentials) through status
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
    }
  }

  /** 実行の受付と状態管理で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  public void close() {
    if (subscription != null) subscription.unsubscribe();
    synchronized (monitor()) {
      queuedCancellation.clear();
      terminalEvents.clear();
      executing.clear();
    }
  }
}
