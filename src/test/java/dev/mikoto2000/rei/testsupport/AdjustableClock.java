package dev.mikoto2000.rei.testsupport;

import java.time.*;
import java.util.concurrent.atomic.AtomicReference;

public final class AdjustableClock extends Clock {
  private final AtomicReference<Instant> now;
  private final ZoneId zone;
  public AdjustableClock(Instant start) { this(new AtomicReference<>(start), ZoneOffset.UTC); }
  private AdjustableClock(AtomicReference<Instant> now, ZoneId zone) { this.now = now; this.zone = zone; }
  public void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
  @Override public Instant instant() { return now.get(); }
  @Override public ZoneId getZone() { return zone; }
  @Override public Clock withZone(ZoneId value) { return new AdjustableClock(now, value); }
}
