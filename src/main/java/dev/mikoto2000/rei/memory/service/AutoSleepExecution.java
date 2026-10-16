package dev.mikoto2000.rei.memory.service;

import dev.mikoto2000.rei.llm.ModelCallBudget;
import java.time.Duration;

/** Per-execution limits, passed through the existing model accounting path. */
public final class AutoSleepExecution implements ModelCallBudget {
  private static final java.util.concurrent.ScheduledExecutorService DEADLINES =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
          r -> {
            var t = new Thread(r, "rei-auto-sleep-deadline");
            t.setDaemon(true);
            return t;
          });
  private final Duration timeout, total;
  private final java.util.function.LongSupplier ticker;
  private final long started;
  private ModelCallBudget delegate;
  private volatile boolean expired;
  private boolean progressSaved;

  public void progressSaved() {
    progressSaved = true;
  }

  public boolean hasSavedProgress() {
    return progressSaved;
  }

  public AutoSleepExecution(Duration timeout, Duration total) {
    this(timeout, total, System::nanoTime);
  }

  public AutoSleepExecution(
      Duration timeout, Duration total, java.util.function.LongSupplier ticker) {
    this.timeout = timeout;
    this.total = total;
    this.ticker = ticker;
    started = ticker.getAsLong();
  }

  public AutoSleepExecution accounting(ModelCallBudget delegate) {
    this.delegate = delegate;
    return this;
  }

  public Duration remaining() {
    var remaining = total.minusNanos(ticker.getAsLong() - started);
    if (remaining.isNegative() || remaining.isZero())
      throw new Failure("AUTO_SLEEP_DEADLINE_EXCEEDED", null);
    return remaining;
  }

  public record Wait(Duration duration, boolean deadline) {}

  public Wait waitLimit() {
    var left = remaining();
    return new Wait(left.compareTo(timeout) <= 0 ? left : timeout, left.compareTo(timeout) <= 0);
  }

  public Duration waitTime() {
    return waitLimit().duration();
  }

  public void run() {
    remaining();
    if (delegate != null) delegate.run();
  }

  public boolean tokenLimitEnabled() {
    return delegate != null && delegate.tokenLimitEnabled();
  }

  public void recordTotalTokens(Integer tokens) {
    if (delegate != null) delegate.recordTotalTokens(tokens);
  }

  /** Bound non-stream work too (including Episode indexing), using existing interruption. */
  public Deadline watchdog() {
    return new Deadline();
  }

  public boolean deadlineExpired() {
    return expired;
  }

  public final class Deadline implements AutoCloseable {
    private boolean active = true;
    private final Thread thread = Thread.currentThread();
    private final java.util.concurrent.ScheduledFuture<?> alarm =
        DEADLINES.schedule(
            () -> {
              synchronized (this) {
                if (active) {
                  expired = true;
                  thread.interrupt();
                }
              }
            },
            remaining().toNanos(),
            java.util.concurrent.TimeUnit.NANOSECONDS);

    public synchronized void close() {
      active = false;
      alarm.cancel(false);
    }
  }

  public static final class Failure extends IllegalStateException {
    public final String code;

    public Failure(String code, Throwable cause) {
      super(code, cause);
      this.code = code;
    }
  }
}
