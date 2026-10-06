package dev.mikoto2000.rei.activity;

import java.time.Instant;

public interface PeriodCoachingStore {
  record Snapshot(long revision,PeriodCoaching.Settings settings) {}
  Snapshot load();
  Snapshot configure(PeriodCoaching.Settings settings);
  Snapshot setEnabled(boolean enabled);
  default Snapshot configureExpected(PeriodCoaching.Settings settings,long revision){throw new UnsupportedOperationException("Revision-checked coaching updates unavailable");}
  default Snapshot setEnabledExpected(boolean enabled,long revision){throw new UnsupportedOperationException("Revision-checked coaching updates unavailable");}
  /** Atomically recheck settings, duplicate keys and shared cooldown before showing an advice. */
  String reserve(Snapshot expected,String periodKey,String reason,Instant now);
}
