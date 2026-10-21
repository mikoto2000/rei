package dev.mikoto2000.rei.topic;

import java.time.Duration;

/**
 * 話題の管理で受け渡す操作がない状態実行条件判断結果を表す。
 *
 * @param accepted 受付済み
 * @param idleDuration 操作がない状態継続時間
 * @param rejectReason 拒否理由
 * @param requiredIdle 必須操作がない状態
 */
public record IdleTriggerDecision(
    boolean accepted,
    Duration idleDuration,
    IdleTriggerRejectReason rejectReason,
    Duration requiredIdle) {

  /** 話題の管理で用いる受付済みを返す。 */
  public static IdleTriggerDecision accepted(Duration idleDuration, Duration requiredIdle) {
    return new IdleTriggerDecision(true, idleDuration, null, requiredIdle);
  }

  /** 話題の管理で用いる拒否済みを返す。 */
  public static IdleTriggerDecision rejected(
      Duration idleDuration, IdleTriggerRejectReason reason, Duration requiredIdle) {
    return new IdleTriggerDecision(false, idleDuration, reason, requiredIdle);
  }
}
