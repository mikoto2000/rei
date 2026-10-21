package dev.mikoto2000.rei.activity;

import java.time.Duration;

/**
 * 作業状況の把握で受け渡す会話間隔判断基準を表す。
 *
 * <p>Both a single gap and accumulated missing time are bounded, including summary-only grouping.
 *
 * @param normal 通常
 * @param maximum 最大
 */
public record SessionGapPolicy(Duration normal, Duration maximum) {
  /** 受け取った情報からSessionGapPolicyを作成する。 */
  public SessionGapPolicy {
    if (normal.isNegative() || maximum.compareTo(normal) < 0)
      throw new IllegalArgumentException("Invalid activity gap thresholds");
  }

  /** 作業状況の把握において、allowsに該当するか判定する。 */
  public boolean allows(Duration gap, long totalMissingSeconds, boolean strongAgreement) {
    return gap.compareTo(maximum) <= 0
        && totalMissingSeconds <= maximum.getSeconds()
        && (gap.compareTo(normal) <= 0 || strongAgreement);
  }
}
