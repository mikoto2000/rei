package dev.mikoto2000.rei.workcontext;

import java.time.Duration;
import java.util.function.LongSupplier;

/** One deadline across all synchronous extraction batches; nested calls restore their owner. */
final class WorkContextExtractionDeadline implements AutoCloseable {
  private static final ThreadLocal<WorkContextExtractionDeadline> CURRENT = new ThreadLocal<>();
  private final WorkContextExtractionDeadline previous;
  private final LongSupplier nanos;
  private final long start;
  private final long budget;

  private WorkContextExtractionDeadline(Duration timeout, LongSupplier nanos) {
    previous = CURRENT.get();
    this.nanos = nanos;
    start = nanos.getAsLong();
    budget = timeout.toNanos();
    CURRENT.set(this);
  }

  static WorkContextExtractionDeadline open(Duration timeout) {
    return open(timeout, System::nanoTime);
  }

  static WorkContextExtractionDeadline open(Duration timeout, LongSupplier nanos) {
    return new WorkContextExtractionDeadline(timeout, nanos);
  }

  static Duration remaining(Duration maximum) {
    long remaining = maximum.toNanos();
    for (var current = CURRENT.get(); current != null; current = current.previous) {
      long left = current.budget - (current.nanos.getAsLong() - current.start);
      if (left <= 0)
        throw new IllegalStateException(
            "Work Context extraction deadline exceeded",
            new java.util.concurrent.TimeoutException());
      remaining = Math.min(remaining, left);
    }
    return Duration.ofNanos(remaining);
  }

  @Override
  public void close() {
    if (previous == null) CURRENT.remove();
    else CURRENT.set(previous);
  }
}
