package dev.mikoto2000.rei.activity;

import java.util.List;

/**
 * 作業状況の把握で受け渡す要約テーマ候補を表す。
 *
 * <p>Final display granularity. memberProjects contains at most two observed representatives, never
 * raw aliases.
 *
 * @param id 識別子
 * @param displayTheme 表示テーマ
 * @param level 段階
 * @param displaySource 表示取得元
 * @param groupIds グループ識別子一覧
 * @param memberProjects 参加者プロジェクト一覧
 * @param memberProjectCount 参加者プロジェクト件数
 * @param activities 作業状況の記録
 * @param themes テーマ一覧
 * @param durationSeconds 継続時間秒数
 * @param observationCount 観測内容件数
 * @param longestContinuousSeconds longest継続秒数
 * @param associationConfidence 関連付け確信度
 * @param specificity 具体性
 * @param groupCoverage グループ対象範囲
 * @param score 評価値
 * @param label 表示名
 */
public record SummaryThemeCandidate(
    String id,
    String displayTheme,
    Level level,
    DisplaySource displaySource,
    List<String> groupIds,
    List<String> memberProjects,
    int memberProjectCount,
    List<String> activities,
    List<String> themes,
    long durationSeconds,
    int observationCount,
    long longestContinuousSeconds,
    double associationConfidence,
    int specificity,
    double groupCoverage,
    double score,
    String label) {
  /** 作業状況の把握における表示取得元の選択肢。 */
  public enum DisplaySource {
    /** プロジェクト。 */
    PROJECT,
    /** テーマグループ。 */
    THEME_GROUP,
    /** GENERICテーマ。 */
    GENERIC_THEME
  }

  /** 作業状況の把握における段階の選択肢。 */
  public enum Level {
    /** グループ。 */
    GROUP,
    /** プロジェクト。 */
    PROJECT,
    /** テーマ。 */
    THEME,
    /** 具体的な対象に限定しない分類。 */
    GENERIC
  }
}
