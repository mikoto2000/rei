package dev.mikoto2000.rei.activity.behavior;

import java.time.Instant;
import java.util.List;

/**
 * 作業習慣の支援で受け渡す作業習慣時系列の記録イベントを表す。
 *
 * <p>Delivery outcome, separate from assessments and pre-generation cooldown reservations. No
 * message body.
 *
 * @param id 識別子
 * @param timestamp 記録日時
 * @param severity 重大度
 * @param trigger 実行条件
 * @param outcome 最終結果
 * @param reason 理由
 * @param continuousEntertainmentSeconds 継続娯楽秒数
 * @param windows Windows
 */
public record BehaviorTimelineEvent(
    String id,
    Instant timestamp,
    BehaviorSeverity severity,
    BehaviorAssessment.Reason trigger,
    Outcome outcome,
    String reason,
    long continuousEntertainmentSeconds,
    List<BehaviorAssessment.Window> windows) {
  /** 作業習慣の支援における最終結果の選択肢。 */
  public enum Outcome {
    /** 通知を出した状態。 */
    EMITTED,
    /** 抑制された。 */
    SUPPRESSED,
    /** 失敗。 */
    FAILED
  }

  /** 受け取った情報からBehaviorTimelineEventを作成する。 */
  public BehaviorTimelineEvent {
    windows = List.copyOf(windows);
  }
}
