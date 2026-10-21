package dev.mikoto2000.rei.activity.behavior;

import java.time.Instant;

/**
 * 作業習慣の支援で受け渡す作業習慣通知判断基準を表す。
 *
 * @param config 設定
 */
public record BehaviorNotificationPolicy(BehaviorProperties config) {
  /** 作業習慣の支援における通知の抑制の選択肢。 */
  public enum Suppression {
    /** 該当なし。 */
    NONE,
    /** 無効状態。 */
    DISABLED,
    /** エージェント使用中。 */
    AGENT_BUSY,
    /** NO現在娯楽。 */
    NO_CURRENT_ENTERTAINMENT,
    /** 経験の記録否定QUALIFIED。 */
    EPISODE_NOT_QUALIFIED,
    /** 未満判定の境界値。 */
    BELOW_THRESHOLD,
    /** 通知を控える期間。 */
    COOLDOWN
  }

  /**
   * 作業習慣の支援で受け渡す判断結果を表す。
   *
   * @param allowed 許可
   * @param suppression 通知の抑制
   * @param state 状態
   * @param cooldownUntil 通知を控える期間終了日時
   */
  public record Decision(
      boolean allowed, Suppression suppression, BehaviorState state, Instant cooldownUntil) {}

  /** 作業習慣の支援で用いる判断を返す。 */
  public Decision decide(BehaviorAssessment a, BehaviorState state, boolean enabled, boolean busy) {
    if (!enabled) return new Decision(false, Suppression.DISABLED, state, cooldownUntil(state));
    if (a.recoveredAt() != null
        && (state.recoveredThrough() == null || a.recoveredAt().isAfter(state.recoveredThrough())))
      state =
          new BehaviorState(
              null,
              a.recoveredAt(),
              null,
              BehaviorSeverity.NONE,
              BehaviorAssessment.Reason.NONE,
              a.severity());
    String episode = state.episodeId();
    if (episode == null && a.episodeStartedAt() != null) episode = a.episodeStartedAt().toString();
    state =
        new BehaviorState(
            episode,
            state.recoveredThrough(),
            state.lastNotificationAt(),
            state.lastNotifiedSeverity(),
            state.lastReason(),
            a.severity());
    Instant until = cooldownUntil(state);
    Suppression reason =
        busy
            ? Suppression.AGENT_BUSY
            : !a.activeEntertainment()
                ? Suppression.NO_CURRENT_ENTERTAINMENT
                : !a.episodeQualified()
                    ? Suppression.EPISODE_NOT_QUALIFIED
                    : a.severity() == BehaviorSeverity.NONE
                        ? Suppression.BELOW_THRESHOLD
                        : until != null
                                && a.evaluatedAt().isBefore(until)
                                && a.severity().ordinal() <= state.lastNotifiedSeverity().ordinal()
                            ? Suppression.COOLDOWN
                            : Suppression.NONE;
    return new Decision(reason == Suppression.NONE, reason, state, until);
  }

  /** 作業習慣の支援で用いる通知を控える期間終了日時を返す。 */
  public Instant cooldownUntil(BehaviorState state) {
    return state.lastNotificationAt() == null
        ? null
        : state
            .lastNotificationAt()
            .plusSeconds(config.cooldownSeconds(state.lastNotifiedSeverity()));
  }
}
