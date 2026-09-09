package dev.mikoto2000.rei.core.service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import reactor.core.Disposable;

@Component
public class CommandCancellationService {

  private static final class State {
    final AtomicReference<Disposable> disposableRef = new AtomicReference<>();
    final AtomicReference<Thread> executionThreadRef = new AtomicReference<>();
    final AtomicBoolean cancellationRequested = new AtomicBoolean(false);
    final String projectId;
    State(String projectId) { this.projectId = projectId; }
  }
  private final java.util.concurrent.ConcurrentMap<String, State> runs = new java.util.concurrent.ConcurrentHashMap<>();
  private final State legacy = new State(null);
  private State state() {
    if(dev.mikoto2000.rei.core.execution.ExecutionScope.current()!=null) return null;
    var run = dev.mikoto2000.rei.core.chat.AgentRunScope.current();
    return run == null ? legacy : runs.get(run.runId());
  }

  public void begin(Thread executionThread) {
    var run = dev.mikoto2000.rei.core.chat.AgentRunScope.current();
    var state = run == null ? legacy : new State(run.projectId());
    state.cancellationRequested.set(false);
    state.disposableRef.set(null);
    state.executionThreadRef.set(executionThread);
    if (run != null) runs.put(run.runId(), state);
  }

  public void register(Disposable disposable) {
    if (disposable == null) {
      return;
    }
    var state = state();
    if (state == null) { disposable.dispose(); return; }
    state.disposableRef.set(disposable);
    if (state.cancellationRequested.get()) {
      disposable.dispose();
    }
  }

  public boolean cancel() {
    if (dev.mikoto2000.rei.core.chat.AgentRunScope.current() != null) return cancel(state());
    // Compatibility for standalone ChatCommand, which has a RunId but no ProjectId.
    boolean changed = cancel(legacy);
    for (var state : runs.values()) if (state.projectId == null) changed |= cancel(state);
    return changed;
  }
  private boolean cancel(State state) {
    if (state == null) return false;
    boolean changed = state.cancellationRequested.compareAndSet(false, true);
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
  public boolean cancelCurrentProject() {
    var project = dev.mikoto2000.rei.core.project.ProjectService.contextForOperation();
    if (project == null) return cancel();
    boolean changed = false;
    for (var state : runs.values()) if (project.id().equals(state.projectId)) changed |= cancel(state);
    return changed;
  }

  public boolean isCancellationRequested() {
    var state = state();
    return state != null && state.cancellationRequested.get();
  }

  public boolean consumeCancellationRequested() {
    var state = state();
    return state != null && state.cancellationRequested.compareAndSet(true, false);
  }

  public void clear() {
    var run = dev.mikoto2000.rei.core.chat.AgentRunScope.current();
    if (run != null) {
      var removed = runs.remove(run.runId());
      if (removed != null) {
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
