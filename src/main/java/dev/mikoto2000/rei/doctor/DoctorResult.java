package dev.mikoto2000.rei.doctor;

import java.time.Instant;

/**
 * 実行環境の診断で受け渡す環境診断結果を表す。
 *
 * <p>Observed scope is explicit: an OK configuration is never a live service health claim.
 *
 * @param id 識別子
 * @param status 状態
 * @param observedAt 観測日時
 * @param method メソッド
 * @param evidence 根拠
 * @param causeCandidates 原因候補一覧
 * @param nextAction 次操作
 */
public record DoctorResult(
    String id,
    Status status,
    Instant observedAt,
    String method,
    String evidence,
    String causeCandidates,
    String nextAction) {
  /** 実行環境の診断における状態の選択肢。 */
  public enum Status {
    /** 問題が見つからなかった状態。 */
    OK,
    /** 注意事項。 */
    WARNING,
    /** エラー。 */
    ERROR,
    /** 否定設定済み。 */
    NOT_CONFIGURED,
    /** 否定APPLICABLE。 */
    NOT_APPLICABLE,
    /** 未確認。 */
    UNVERIFIED,
    /** 対象外。 */
    SKIPPED
  }
}
