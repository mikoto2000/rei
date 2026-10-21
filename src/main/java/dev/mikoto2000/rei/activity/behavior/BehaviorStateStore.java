package dev.mikoto2000.rei.activity.behavior;

/** 作業習慣状態の記録を保持し、後続の処理で参照できるようにする。 */
public interface BehaviorStateStore {
  /** 保存された情報を読み込み、現在の処理で使える形にする。 */
  BehaviorState load();

  /** 後から参照できるよう、指定された情報を保存する。 */
  void save(BehaviorState state, BehaviorAssessment assessment);

  /** 既存の記録へ経緯を残すため、イベントを追記する。 */
  default void appendEvent(BehaviorTimelineEvent event) {}

  /** 対象を特定できるよう、イベント一覧期間を探す。 */
  default java.util.List<BehaviorTimelineEvent> findEventsBetween(
      java.time.Instant start, java.time.Instant end) {
    return java.util.List.of();
  }
}
