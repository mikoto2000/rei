package dev.mikoto2000.rei.activity.behavior;

import java.time.Instant;

/**
 * 作業習慣の支援で受け渡す作業習慣状態を表す。
 *
 * <p>One durable checkpoint; no per-minute history or screenshots.
 *
 * @param episodeId 経験の記録識別子
 * @param recoveredThrough recovered終点
 * @param lastNotificationAt 直近通知日時
 * @param lastNotifiedSeverity 直近通知済み重大度
 * @param lastReason 直近理由
 * @param currentSeverity 現在重大度
 */
public record BehaviorState(
    String episodeId,
    Instant recoveredThrough,
    Instant lastNotificationAt,
    BehaviorSeverity lastNotifiedSeverity,
    BehaviorAssessment.Reason lastReason,
    BehaviorSeverity currentSeverity) {
  /** 対象がない場合の結果を表す。 */
  public static BehaviorState empty() {
    return new BehaviorState(
        null,
        null,
        null,
        BehaviorSeverity.NONE,
        BehaviorAssessment.Reason.NONE,
        BehaviorSeverity.NONE);
  }

  /** 作業習慣の支援で用いる通知済みを返す。 */
  public BehaviorState notified(BehaviorAssessment a) {
    return new BehaviorState(
        episodeId, recoveredThrough, a.evaluatedAt(), a.severity(), a.reason(), a.severity());
  }
}
