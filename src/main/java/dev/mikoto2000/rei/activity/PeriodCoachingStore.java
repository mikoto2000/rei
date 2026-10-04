package dev.mikoto2000.rei.activity;

import java.time.Instant;

public interface PeriodCoachingStore {
  record Snapshot(long revision,PeriodCoaching.Settings settings) {}
  Snapshot load();
  Snapshot configure(PeriodCoaching.Settings settings);
  Snapshot setEnabled(boolean enabled);
  /** Atomically recheck settings, duplicate keys and shared cooldown before showing an advice. */
  String reserve(Snapshot expected,String periodKey,String reason,Instant now);
}
