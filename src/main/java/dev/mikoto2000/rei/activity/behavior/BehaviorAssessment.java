package dev.mikoto2000.rei.activity.behavior;

import java.time.Instant;
import java.util.List;

/**
 * 作業習慣の支援で受け渡す作業習慣評価を表す。
 *
 * <p>Durations are observed estimates in seconds, never wall-clock engagement measurements.
 *
 * @param severity 重大度
 * @param reason 理由
 * @param evaluatedAt 評価済み日時
 * @param continuousEntertainmentSeconds 継続娯楽秒数
 * @param windows Windows
 * @param dominantCategories 主要な対象分類一覧
 * @param services サービス一覧
 * @param confidence 確信度
 * @param latestObservedAt 最新観測日時
 * @param activeEntertainment 活動中娯楽
 * @param recoveredAt recovered日時
 * @param episodeStartedAt 経験の記録開始日時
 * @param episodeQualified 経験の記録Qualified
 */
public record BehaviorAssessment(
    BehaviorSeverity severity,
    Reason reason,
    Instant evaluatedAt,
    long continuousEntertainmentSeconds,
    List<Window> windows,
    List<String> dominantCategories,
    List<String> services,
    double confidence,
    Instant latestObservedAt,
    boolean activeEntertainment,
    Instant recoveredAt,
    Instant episodeStartedAt,
    boolean episodeQualified) {
  /** 作業習慣の支援における理由の選択肢。 */
  public enum Reason {
    /** 該当なし。 */
    NONE,
    /** 継続娯楽。 */
    CONTINUOUS_ENTERTAINMENT,
    /** 娯楽割合HIGH。 */
    ENTERTAINMENT_RATIO_HIGH,
    /** 両方に該当する状態。 */
    BOTH
  }

  /**
   * 作業習慣の支援で受け渡す範囲を表す。
   *
   * @param durationMinutes 継続時間分数
   * @param entertainmentObservedSeconds 娯楽観測秒数
   * @param eligibleObservedSeconds 対象の条件観測秒数
   * @param entertainmentRatio 娯楽割合
   * @param severity 重大度
   * @param observedSeconds 観測秒数
   */
  public record Window(
      int durationMinutes,
      long entertainmentObservedSeconds,
      long eligibleObservedSeconds,
      double entertainmentRatio,
      BehaviorSeverity severity,
      long observedSeconds) {}

  /** 受け取った情報からBehaviorAssessmentを作成する。 */
  public BehaviorAssessment {
    windows = List.copyOf(windows);
    dominantCategories = List.copyOf(dominantCategories);
    services = List.copyOf(services);
  }
}
