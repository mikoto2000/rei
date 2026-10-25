package dev.mikoto2000.rei.life;

import java.time.Instant;

/** Read-only constraints from already saved personal schedules. Never acquire new observations. */
@FunctionalInterface
public interface LifeAvailability {
  boolean busy(Instant at);

  default Instant availableUntil(Instant from, Instant limit) {
    return busy(from) ? from : limit;
  }
}
