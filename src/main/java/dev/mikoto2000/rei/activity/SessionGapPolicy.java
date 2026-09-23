package dev.mikoto2000.rei.activity;

import java.time.Duration;

/** Both a single gap and accumulated missing time are bounded, including summary-only grouping. */
public record SessionGapPolicy(Duration normal,Duration maximum) {
  public SessionGapPolicy {
    if(normal.isNegative() || maximum.compareTo(normal)<0) throw new IllegalArgumentException("Invalid activity gap thresholds");
  }
  public boolean allows(Duration gap,long totalMissingSeconds,boolean strongAgreement) {
    return gap.compareTo(maximum)<=0 && totalMissingSeconds<=maximum.getSeconds()
        && (gap.compareTo(normal)<=0 || strongAgreement);
  }
}
