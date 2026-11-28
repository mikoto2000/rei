package dev.mikoto2000.rei.llm;

import java.time.Duration;
import java.util.function.Supplier;

/** Synchronous transport configuration, with no conversation or authorization state. */
final class AutonomousCallDeadline {
  private static final ThreadLocal<Duration> CURRENT = new ThreadLocal<>();

  private AutonomousCallDeadline() {}

  static Duration current() {
    return CURRENT.get();
  }

  static <T> T call(Duration deadline, Supplier<T> action) {
    Duration previous = CURRENT.get();
    CURRENT.set(deadline);
    try {
      return action.get();
    } finally {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    }
  }
}
