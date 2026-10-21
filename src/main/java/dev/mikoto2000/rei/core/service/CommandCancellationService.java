package dev.mikoto2000.rei.core.service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

/** コマンドキャンセルのユースケースを実現する。 */
@Component
public class CommandCancellationService {

  /** 対話から使う共通操作に必要な状態を扱う。 */
  private static final class State {
    /** disposable参照先。 */
    final AtomicReference<Disposable> disposableRef = new AtomicReference<>();

    /** 実行スレッド参照先。 */
    final AtomicReference<Thread> executionThreadRef = new AtomicReference<>();

    /** キャンセル要求された。 */
    final AtomicBoolean cancellationRequested = new AtomicBoolean(false);

    /** 子の一覧。 */
    final java.util.concurrent.CopyOnWriteArrayList<Runnable> children =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 所属プロジェクトの識別子。 */
    final String projectId;

    /** 指定された値や利用先を使って、Stateを初期化する。 */
    State(String projectId) {
      this.projectId = projectId;
    }
  }

  /** 実行一覧。 */
  private final java.util.concurrent.ConcurrentMap<String, State> runs =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** 待機中Cancellations。 */
  private final java.util.Set<String> pendingCancellations = new java.util.HashSet<>();

  /** 旧形式。 */
  private final State legacy = new State(null);

  /** 対話から使う共通操作で用いる状態を返す。 */
  private State state() {
    if (dev.mikoto2000.rei.core.execution.ExecutionScope.current() != null) return null;
    var run = dev.mikoto2000.rei.core.chat.AgentRunScope.current();
    return run == null ? legacy : runs.get(run.runId());
  }

  /** 対話から使う共通操作に関する開始を処理する。 */
  public void begin(Thread executionThread) {
    var run = dev.mikoto2000.rei.core.chat.AgentRunScope.current();
    var state = run == null ? legacy : new State(run.projectId());
    synchronized (this) {
      state.cancellationRequested.set(run != null && pendingCancellations.remove(run.runId()));
      state.disposableRef.set(null);
      state.executionThreadRef.set(executionThread);
      if (run != null) runs.put(run.runId(), state);
    }
    if (state.cancellationRequested.get()) cancel(state);
  }

  /**
   * 利用者が不要とした実行を止めるため、実行をキャンセルする。
   *
   * <p>A stop request may precede runner registration; begin must not reset it.
   */
  public boolean cancelRun(String runId) {
    State state;
    synchronized (this) {
      state = runs.get(runId);
      if (state == null) return pendingCancellations.add(runId);
    }
    return cancel(state);
  }

  /** 対話から使う共通操作に関する記憶の削除待機中キャンセルを処理する。 */
  public synchronized void forgetPendingCancellation(String runId) {
    pendingCancellations.remove(runId);
  }

  /**
   * 利用者が不要とした実行を止めるため、登録済み実行をキャンセルする。
   *
   * <p>Stop only an existing child; a terminal Goal must not leave an orphan future cancellation.
   */
  public boolean cancelRegisteredRun(String runId) {
    var existing = runs.get(runId);
    return existing != null && cancel(existing);
  }

  /** 後から対象を参照できるよう、コマンドキャンセルサービスを登録する。 */
  public void register(Disposable disposable) {
    if (disposable == null) {
      return;
    }
    var state = state();
    if (state == null) {
      disposable.dispose();
      return;
    }
    state.disposableRef.set(disposable);
    if (state.cancellationRequested.get()) {
      disposable.dispose();
    }
  }

  /** 対象の実行をキャンセルする。 */
  public boolean cancel() {
    if (dev.mikoto2000.rei.core.chat.AgentRunScope.current() != null) return cancel(state());
    // Compatibility for standalone ChatCommand, which has a RunId but no ProjectId.
    boolean changed = cancel(legacy);
    for (var state : runs.values()) if (state.projectId == null) changed |= cancel(state);
    return changed;
  }

  /**
   * 通知に応じて状態や表示を更新するため、キャンセルを受け付ける。
   *
   * <p>Adds a child without replacing the parent's streaming subscription. Safe against
   * registration/cancel races.
   */
  public Disposable onCancel(String runId, Runnable child) {
    State state = runId == null ? legacy : runs.get(runId);
    if (state == null) return () -> {};
    state.children.add(child);
    if (state.cancellationRequested.get()) child.run();
    return () -> state.children.remove(child);
  }

  /** 対象の実行をキャンセルする。 */
  private boolean cancel(State state) {
    if (state == null) return false;
    boolean changed = state.cancellationRequested.compareAndSet(false, true);
    state.children.forEach(Runnable::run);
    Disposable disposable = state.disposableRef.getAndSet(null);
    if (disposable != null) {
      disposable.dispose();
    }
    Thread executionThread = state.executionThreadRef.get();
    if (executionThread != null) {
      executionThread.interrupt();
    }
    return changed;
  }

  /** 利用者が不要とした実行を止めるため、現在プロジェクトをキャンセルする。 */
  public boolean cancelCurrentProject() {
    var project = dev.mikoto2000.rei.core.project.ProjectService.contextForOperation();
    if (project == null) return cancel();
    return cancelProject(project.id());
  }

  /**
   * 利用者が不要とした実行を止めるため、プロジェクトをキャンセルする。
   *
   * <p>Explicit human Project control; never infer another client's mutable selection.
   */
  public boolean cancelProject(String projectId) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (projectId == null || projectId.isBlank() || projectId.length() > 128)
      throw new IllegalArgumentException("Project ID required");
    boolean changed = false;
    for (var state : runs.values()) if (projectId.equals(state.projectId)) changed |= cancel(state);
    return changed;
  }

  /** 対話から使う共通操作において、キャンセル要求されたかどうかを判定する。 */
  public boolean isCancellationRequested() {
    var state = state();
    return state != null && state.cancellationRequested.get();
  }

  /** 対話から使う共通操作において、consumeキャンセル要求されたに該当するか判定する。 */
  public boolean consumeCancellationRequested() {
    var state = state();
    return state != null && state.cancellationRequested.compareAndSet(true, false);
  }

  /** 保持している情報を初期状態へ戻すため、コマンドキャンセルサービスを消去する。 */
  public void clear() {
    var run = dev.mikoto2000.rei.core.chat.AgentRunScope.current();
    if (run != null) {
      forgetPendingCancellation(run.runId());
      var removed = runs.remove(run.runId());
      if (removed != null) {
        removed.children.forEach(Runnable::run);
        removed.children.clear();
        var disposable = removed.disposableRef.getAndSet(null);
        if (disposable != null) disposable.dispose();
      }
      return;
    }
    var disposable = legacy.disposableRef.getAndSet(null);
    if (disposable != null) disposable.dispose();
    legacy.executionThreadRef.set(null);
    legacy.cancellationRequested.set(false);
  }
}
