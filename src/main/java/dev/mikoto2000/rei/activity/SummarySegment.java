package dev.mikoto2000.rei.activity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 作業状況の把握で受け渡す要約区間を表す。
 *
 * <p>Display projection only. Evidence and fine session references remain available verbatim.
 *
 * @param startedAt 開始日時
 * @param endedAt 終了日時
 * @param observedSeconds 観測秒数
 * @param roles 役割一覧
 * @param evidence 根拠
 * @param fineSessionIds fine会話識別子一覧
 * @param theme テーマ
 * @param primaryCategories 主要な分類一覧
 */
public record SummarySegment(
    Instant startedAt,
    Instant endedAt,
    long observedSeconds,
    ActivityRoles roles,
    List<ActivityRecord> evidence,
    List<String> fineSessionIds,
    String theme,
    List<String> primaryCategories) {
  /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SummarySegment(
      Instant startedAt,
      Instant endedAt,
      long observedSeconds,
      ActivityRoles roles,
      List<ActivityRecord> evidence,
      List<String> fineSessionIds) {
    this(
        startedAt,
        endedAt,
        observedSeconds,
        roles,
        evidence,
        fineSessionIds,
        SummaryGroupingPolicy.theme(roles));
  }

  /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SummarySegment(
      Instant startedAt,
      Instant endedAt,
      long observedSeconds,
      ActivityRoles roles,
      List<ActivityRecord> evidence,
      List<String> fineSessionIds,
      String theme) {
    this(
        startedAt,
        endedAt,
        observedSeconds,
        roles,
        evidence,
        fineSessionIds,
        theme,
        List.of(roles.category()));
  }

  /** 受け取った情報からSummarySegmentを作成する。 */
  public SummarySegment {
    evidence = List.copyOf(evidence);
    fineSessionIds = List.copyOf(fineSessionIds);
    primaryCategories = List.copyOf(primaryCategories);
  }

  /** 作業状況の把握で用いる未観測秒数を返す。 */
  public long unobservedSeconds() {
    return Math.max(0, Duration.between(startedAt, endedAt).getSeconds() - observedSeconds);
  }
}
