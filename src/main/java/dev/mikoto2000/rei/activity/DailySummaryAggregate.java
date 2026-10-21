package dev.mikoto2000.rei.activity;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作業状況の把握で受け渡す日次要約集計結果を表す。
 *
 * <p>Bounded, evidence-free writer input. Durations are seconds of observations, not wall-clock
 * engagement.
 *
 * @param targetDate 対象日付
 * @param observedSeconds 観測秒数
 * @param unobservedSeconds 未観測秒数
 * @param categorySeconds 分類秒数
 * @param entertainmentSeconds 娯楽秒数
 * @param topProjects 上位プロジェクト一覧
 * @param services サービス一覧
 * @param timeOfDay 時刻所属日
 * @param projectThemeStats プロジェクトテーマ統計
 * @param mainWorkThemeCandidates main作業テーマ候補一覧
 * @param consolidationMetrics 統合計測値
 * @param significantAiAssistance 主要なAI支援
 * @param dominantThemes 主要な対象テーマ一覧
 * @param leisureActivities 余暇作業状況の記録
 * @param majorWorkBlocks major作業まとまり一覧
 * @param majorLeisureBlocks major余暇まとまり一覧
 * @param projectSwitchCount プロジェクトSwitch件数
 * @param frequentProjectSwitches frequentプロジェクトSwitches
 * @param unknownRatio 不明割合
 * @param sourceSegmentCount 取得元区間件数
 */
public record DailySummaryAggregate(
    LocalDate targetDate,
    long observedSeconds,
    long unobservedSeconds,
    Map<String, Long> categorySeconds,
    Map<EntertainmentDisposition, Long> entertainmentSeconds,
    List<Weighted> topProjects,
    List<Weighted> services,
    Map<String, Bucket> timeOfDay,
    @com.fasterxml.jackson.annotation.JsonIgnore List<ProjectThemeStat> projectThemeStats,
    List<SummaryThemeCandidate> mainWorkThemeCandidates,
    @com.fasterxml.jackson.annotation.JsonIgnore
        DailySummaryThemeConsolidator.Metrics consolidationMetrics,
    boolean significantAiAssistance,
    List<String> dominantThemes,
    List<String> leisureActivities,
    List<Block> majorWorkBlocks,
    List<Block> majorLeisureBlocks,
    int projectSwitchCount,
    boolean frequentProjectSwitches,
    double unknownRatio,
    int sourceSegmentCount) {
  /**
   * 作業状況の把握で受け渡すWeightedを表す。
   *
   * @param name 名前
   * @param seconds 秒数
   */
  public record Weighted(String name, long seconds) {}

  /**
   * 作業状況の把握で受け渡すプロジェクトテーマStatを表す。
   *
   * @param canonicalProject 正規プロジェクト
   * @param categories 分類一覧
   * @param themeCandidates テーマ候補一覧
   * @param observedSeconds 観測秒数
   * @param observationCount 観測内容件数
   * @param longestContinuousSeconds longest継続秒数
   * @param specificity 具体性
   * @param score 評価値
   * @param label 表示名
   * @param strongAssociations 確かな関連付け一覧
   * @param categorySeconds 分類秒数
   */
  public record ProjectThemeStat(
      String canonicalProject,
      List<String> categories,
      List<String> themeCandidates,
      long observedSeconds,
      int observationCount,
      long longestContinuousSeconds,
      int specificity,
      double score,
      String label,
      List<ProjectThemeAssociation> strongAssociations,
      @com.fasterxml.jackson.annotation.JsonIgnore Map<String, Long> categorySeconds) {}

  /**
   * 作業状況の把握で受け渡す集計区分を表す。
   *
   * @param observedSeconds 観測秒数
   * @param categorySeconds 分類秒数
   * @param dominant 主要な対象
   * @param secondary 補助的な
   * @param background バックグラウンド
   * @param workThemes 作業テーマ一覧
   * @param dominantProjects 主要な対象プロジェクト一覧
   * @param secondaryThemes 補助的なテーマ一覧
   * @param strongAssociations 確かな関連付け一覧
   * @param timeOfDayThemeCandidates 時刻所属日テーマ候補一覧
   */
  public record Bucket(
      long observedSeconds,
      Map<String, Long> categorySeconds,
      List<String> dominant,
      List<String> secondary,
      List<String> background,
      List<String> workThemes,
      List<String> dominantProjects,
      List<String> secondaryThemes,
      List<ProjectThemeAssociation> strongAssociations,
      List<SummaryThemeCandidate> timeOfDayThemeCandidates) {}

  /**
   * 作業状況の把握で受け渡すまとまりを表す。
   *
   * @param start 開始
   * @param end 終了
   * @param observedSeconds 観測秒数
   * @param theme テーマ
   */
  public record Block(String start, String end, long observedSeconds, String theme) {}

  /** 集計区分表示名一覧。 */
  public static final Map<String, String> BUCKET_LABELS =
      Collections.unmodifiableMap(
          new LinkedHashMap<>(
              Map.of("lateNight", "深夜", "morning", "午前", "afternoon", "午後", "evening", "夜")));

  /** 集計区分順序。 */
  public static final List<String> BUCKET_ORDER =
      List.of("lateNight", "morning", "afternoon", "evening");

  /** 作業状況の把握で用いる分類表示名を返す。 */
  public static String categoryLabel(String category) {
    return switch (category) {
      case "development" -> "開発";
      case "research" -> "調査";
      case "documentation" -> "文書作業";
      case "social" -> "SNS閲覧";
      case "media" -> "動画・音楽の閲覧";
      case "shopping" -> "ショッピング";
      case "gaming" -> "ゲーム";
      case "communication" -> "コミュニケーション";
      case "monitoring" -> "監視";
      case "navigation" -> "予定・経路確認";
      case "idle" -> "待機";
      default -> "判定不能";
    };
  }
}
