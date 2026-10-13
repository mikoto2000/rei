package dev.mikoto2000.rei.chatgpt;

import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.TIMEOUT;
import java.time.Duration;
import java.util.function.LongSupplier;

/** The caller's remaining deadline includes startup; process cleanup gets no new model budget. */
final class PlanProcessDeadline {
  private final LongSupplier nanoClock;
  private final long deadline;

  PlanProcessDeadline(Duration timeout) { this(timeout, System::nanoTime); }

  PlanProcessDeadline(Duration timeout, LongSupplier nanoClock) {
    if (timeout == null || timeout.isNegative() || timeout.isZero()
        || timeout.compareTo(PlanExecutionProperties.MAX_TIMEOUT) > 0)
      throw new IllegalArgumentException("Plan process timeout must be positive and at most 30 minutes");
    this.nanoClock = nanoClock;
    deadline = nanoClock.getAsLong() + timeout.toNanos();
  }

  Duration remaining() {
    long nanos = deadline - nanoClock.getAsLong();
    if (nanos <= 0) throw ChatGptException.of(TIMEOUT);
    return Duration.ofNanos(nanos);
  }

  String wslWatchdogDuration() {
    // The RPC enforces the exact remaining deadline. Only its TERM/KILL cleanup may take longer.
    long nanos = remaining().toNanos();
    return (Math.floorDiv(nanos - 1, 1_000_000_000L) + 1 + 5) + "s";
  }
  long probeWaitMillis() {return Math.min(10000,Math.max(1,remaining().toMillis()));}
}
