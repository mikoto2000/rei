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

  public void begin(Thread executionThread) {
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

  public boolean isCancellationRequested() {
    return cancellationRequested.get();
  }

  public boolean consumeCancellationRequested() {
    return cancellationRequested.compareAndSet(true, false);
  }

  public void clear() {
    disposableRef.set(null);
    executionThreadRef.set(null);
    cancellationRequested.set(false);
  }
}
