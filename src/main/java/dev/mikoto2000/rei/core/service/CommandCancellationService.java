package dev.mikoto2000.rei.core.service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import reactor.core.Disposable;

@Component
public class CommandCancellationService {

  private final AtomicReference<Disposable> disposableRef = new AtomicReference<>();
  private final AtomicReference<Thread> executionThreadRef = new AtomicReference<>();
  private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);
  private final AtomicReference<String> owningProject = new AtomicReference<>();

  public void begin(Thread executionThread) {
    var run = dev.mikoto2000.rei.core.chat.AgentRunScope.current();
    owningProject.set(run == null ? null : run.projectId());
    cancellationRequested.set(false);
    disposableRef.set(null);
    executionThreadRef.set(executionThread);
  }

  public void register(Disposable disposable) {
    if (disposable == null) {
      return;
    }
    disposableRef.set(disposable);
    if (cancellationRequested.get()) {
      disposable.dispose();
    }
  }

  public boolean cancel() {
    boolean changed = cancellationRequested.compareAndSet(false, true);
    Disposable disposable = disposableRef.get();
    if (disposable != null) {
      disposable.dispose();
    }
    Thread executionThread = executionThreadRef.get();
    if (executionThread != null) {
      executionThread.interrupt();
    }
    return changed;
  }
  public boolean cancelCurrentProject() {
    var project = dev.mikoto2000.rei.core.project.ProjectService.contextForOperation();
    if (project != null && !project.id().equals(owningProject.get())) return false;
    return cancel();
  }

  public boolean isCancellationRequested() {
    return cancellationRequested.get();
  }

  public boolean consumeCancellationRequested() {
    return cancellationRequested.compareAndSet(true, false);
  }

  public void clear() {
    owningProject.set(null);
    disposableRef.set(null);
    executionThreadRef.set(null);
    cancellationRequested.set(false);
  }
}
