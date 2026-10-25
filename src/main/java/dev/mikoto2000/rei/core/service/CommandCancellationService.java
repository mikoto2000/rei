package dev.mikoto2000.rei.core.service;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

/** Run の所有期間と停止要求を管理し、終了確認までキャンセル情報を保持する。 */
@Component
public class CommandCancellationService {
  /** 停止要求の受付と実際の終了を区別する照会情報。 */
  public record Diagnostic(String runId, String projectId, String status,
      Instant requestedAt, Instant finishedAt, String failure) {}

  /** 非同期の子処理が実際に戻るまで保持する所有権。 */
  @FunctionalInterface
  public interface ChildLease extends AutoCloseable {
    @Override
    void close();
  }

  /** 子処理の実体が戻るまで所有権を保持する。停止済みなら開始を拒否する。 */
  public ChildLease enterChild(String runId) {
    var state = runId == null ? null : runs.get(runId);
    if (state == null) return () -> {};
    var thread = Thread.currentThread();
    synchronized (state) {
      if (state.requestedAt != null || state.finishedAt != null || state.closing)
        throw new java.util.concurrent.CancellationException("Run is stopping");
      state.workers.merge(thread, 1, Integer::sum);
    }
    var closed = new AtomicBoolean();
    return () -> {
      if (!closed.compareAndSet(false, true)) return;
      synchronized (state) {
        state.workers.computeIfPresent(thread, (key, count) -> count == 1 ? null : count - 1);
        state.notifyAll();
      }
    };
  }

  public boolean hasActiveChildren(String runId) {
    var state = runs.get(runId);
    if (state == null) return false;
    synchronized (state) { return !state.workers.isEmpty(); }
  }

  /** タイムアウトを終了と見なさず、実体が戻るまで登録と排他枠を保持する。 */
  public void awaitChildren(String runId) {
    var state = runs.get(runId);
    if (state == null) return;
    boolean interrupted = false;
    synchronized (state) {
      state.closing = true;
      while (!state.workers.isEmpty()) {
        try { state.wait(); }
        catch (InterruptedException stop) { interrupted = true; }
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  /** 各実行が所有する資源。状態の操作はこのオブジェクトで同期する。 */
  private static final class State {
    final String runId;
    final String projectId;
    final List<Runnable> children = new ArrayList<>();
    final Map<Thread, Integer> workers = new java.util.IdentityHashMap<>();
    boolean closing;
    Disposable disposable;
    Thread thread;
    Instant requestedAt;
    Instant finishedAt;
    String failure;
    int depth;
    boolean managed;

    State(String runId, String projectId) {
      this.runId = runId;
      this.projectId = projectId;
    }
  }

  private final Map<String, State> runs = new java.util.concurrent.ConcurrentHashMap<>();
  private final Map<String, Instant> pendingCancellations = new java.util.HashMap<>();
  /** 終了済み ID への遅延要求を診断するための有界履歴。資源参照は保持しない。 */
  private final Map<String, State> finished = new LinkedHashMap<>();
  private final State legacy = new State(null, null);
  private final Clock clock;

  public CommandCancellationService() {
    this(Clock.systemUTC());
  }

  public CommandCancellationService(Clock clock) {
    this.clock = clock;
  }

  private State state() {
    if (dev.mikoto2000.rei.core.execution.ExecutionScope.current() != null) return null;
    var run = AgentRunScope.current();
    return run == null ? legacy : runs.get(run.runId());
  }

  /** 一覧に公開する前に予約する。解除は Router の finally が所有する。 */
  public synchronized void prepare(AgentRunContext context) {
    var state = runs.computeIfAbsent(context.runId(), id -> new State(id, context.projectId()));
    synchronized (state) {
      state.managed = true;
      var pending = pendingCancellations.remove(context.runId());
      if (pending != null && state.requestedAt == null) state.requestedAt = pending;
    }
  }

  /** 実行スレッドを結び付ける。開始前の要求を上書きしない。 */
  public void begin(Thread executionThread) {
    var run = AgentRunScope.current();
    State state;
    synchronized (this) {
      state = run == null ? legacy : runs.computeIfAbsent(run.runId(),
          id -> new State(id, run.projectId()));
      synchronized (state) {
        state.depth++;
        var pending = run == null ? null : pendingCancellations.remove(run.runId());
        if (pending != null && state.requestedAt == null) state.requestedAt = pending;
        if (state.thread == null) state.thread = executionThread;
        if (state.requestedAt != null && executionThread != null) executionThread.interrupt();
      }
    }
  }

  /** ID による開始直前の要求。呼び出し側は実在する受付済み Run を検証する。 */
  public boolean cancelRun(String runId) {
    State state;
    synchronized (this) {
      state = runs.get(runId);
      if (state == null) return !finished.containsKey(runId)
          && pendingCancellations.putIfAbsent(runId, clock.instant()) == null;
    }
    return cancel(state);
  }

  public synchronized void forgetPendingCancellation(String runId) {
    pendingCancellations.remove(runId);
  }

  public boolean cancelRegisteredRun(String runId) {
    return cancel(runs.get(runId));
  }

  /** 新しいストリームも、停止要求と登録の競合時に確実に破棄する。 */
  public void register(Disposable disposable) {
    if (disposable == null) return;
    var state = state();
    if (state == null) {
      disposable.dispose();
      return;
    }
    Disposable previous;
    synchronized (state) {
      previous = state.disposable;
      if (state.requestedAt == null && state.finishedAt == null) state.disposable = disposable;
      else previous = disposable;
    }
    if (previous != null) invoke(state, previous::dispose);
  }

  public boolean cancel() {
    if (AgentRunScope.current() != null) return cancel(state());
    boolean changed = cancel(legacy);
    for (var state : runs.values()) if (state.projectId == null) changed |= cancel(state);
    return changed;
  }

  /** 子の登録と要求が競合しても一度だけ実行し、一つの失敗で他の停止を妨げない。 */
  public Disposable onCancel(String runId, Runnable child) {
    State state = runId == null ? legacy : runs.get(runId);
    if (state == null) return () -> {};
    var called = new AtomicBoolean();
    Runnable once = () -> { if (called.compareAndSet(false, true)) invoke(state, child); };
    boolean stopped;
    synchronized (state) {
      stopped = state.requestedAt != null || state.finishedAt != null;
      if (!stopped) state.children.add(once);
    }
    if (stopped) once.run();
    return () -> { synchronized (state) { state.children.remove(once); } };
  }

  private boolean cancel(State state) {
    if (state == null) return false;
    List<Runnable> children;
    Disposable disposable;
    synchronized (state) {
      if (state.finishedAt != null || state.requestedAt != null) return false;
      state.requestedAt = clock.instant();
      children = List.copyOf(state.children);
      state.children.clear();
      disposable = state.disposable;
      state.disposable = null;
      // 終了との競合で、終了後に再利用されたスレッドを割り込まない。
      if (state.thread != null) invoke(state, state.thread::interrupt);
      for (var worker : state.workers.keySet())
        if (worker != state.thread) invoke(state, worker::interrupt);
    }
    log(state, "requested");
    children.forEach(Runnable::run);
    if (disposable != null) invoke(state, disposable::dispose);
    return true;
  }

  private void invoke(State state, Runnable action) {
    try { action.run(); }
    catch (RuntimeException error) {
      synchronized (state) { state.failure = error.getClass().getSimpleName(); }
      log(state, "signal_failed");
    }
  }

  public boolean cancelCurrentProject() {
    var project = dev.mikoto2000.rei.core.project.ProjectService.contextForOperation();
    return project == null ? cancel() : cancelProject(project.id());
  }

  public boolean cancelProject(String projectId) {
    validateProject(projectId);
    boolean changed = false;
    for (var state : runs.values()) if (projectId.equals(state.projectId)) changed |= cancel(state);
    return changed;
  }

  private void validateProject(String projectId) {
    if (projectId == null || projectId.isBlank() || projectId.length() > 128)
      throw new IllegalArgumentException("Project ID required");
  }

  public boolean isCancellationRequested() {
    var state = state();
    if (state == null) return false;
    synchronized (state) { return state.requestedAt != null; }
  }

  /** Run の停止は sticky。旧単独コマンドだけ消費 API の互換動作を残す。 */
  public boolean consumeCancellationRequested() {
    var state = state();
    if (state == null) return false;
    synchronized (state) {
      boolean requested = state.requestedAt != null;
      if (state == legacy) state.requestedAt = null;
      return requested;
    }
  }

  /** 内側の処理の終了では親の登録を消さない。 */
  public void clear() {
    var state = state();
    if (state == null) return;
    synchronized (state) {
      if (state.depth > 0) state.depth--;
      if (state.managed || state.depth > 0) return;
    }
    if (state == legacy) {
      release(state);
      synchronized (state) {
        state.requestedAt = null;
        state.finishedAt = null;
        state.failure = null;
      }
    } else finish(state.runId);
  }

  /** 実際に runner と cleanup が戻った時だけ呼び出す。 */
  public void finish(String runId) {
    awaitChildren(runId);
    State state;
    synchronized (this) {
      pendingCancellations.remove(runId);
      state = runs.remove(runId);
      if (state == null) return;
      synchronized (state) {
        state.finishedAt = clock.instant();
        state.thread = null;
      }
      finished.put(runId, state);
      while (finished.size() > 256) finished.remove(finished.keySet().iterator().next());
    }
    release(state);
    log(state, "finished");
  }

  private void release(State state) {
    List<Runnable> children;
    Disposable disposable;
    synchronized (state) {
      state.thread = null;
      children = List.copyOf(state.children);
      state.children.clear();
      disposable = state.disposable;
      state.disposable = null;
    }
    children.forEach(Runnable::run);
    if (disposable != null) invoke(state, disposable::dispose);
  }

  public synchronized Diagnostic diagnostic(String runId) {
    var state = runs.get(runId);
    if (state == null) state = finished.get(runId);
    if (state == null) return new Diagnostic(runId, null,
        pendingCancellations.containsKey(runId) ? "CANCEL_REQUESTED" : "NOT_FOUND",
        pendingCancellations.get(runId), null, null);
    synchronized (state) {
      String status = state.finishedAt != null ? "FINISHED"
          : state.failure != null ? "CANCEL_FAILED"
          : state.requestedAt == null ? "RUNNING"
          : java.time.Duration.between(state.requestedAt, clock.instant()).toSeconds() >= 30
              ? "CANCEL_STALLED" : "CANCEL_REQUESTED";
      return new Diagnostic(runId, state.projectId, status,
          state.requestedAt, state.finishedAt, state.failure);
    }
  }

  public List<Diagnostic> projectDiagnostics(String projectId) {
    validateProject(projectId);
    return runs.values().stream().filter(s -> projectId.equals(s.projectId))
        .map(s -> diagnostic(s.runId)).toList();
  }

  private void log(State state, String outcome) {
    org.slf4j.LoggerFactory.getLogger(CommandCancellationService.class).info(
        "Run cancellation: runId={}, projectId={}, requestedAt={}, finishedAt={}, outcome={}, failure={}",
        state.runId, state.projectId, state.requestedAt, state.finishedAt, outcome, state.failure);
  }
}
