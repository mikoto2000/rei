package dev.mikoto2000.rei.activity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 作業状況の把握で受け渡す傾向要約区間を表す。
 *
 * <p>A bounded period's observed trend, not a continuous activity or an analytics input.
 *
 * @param startedAt 開始日時
 * @param endedAt 終了日時
 * @param continuity 継続性
 * @param theme テーマ
 * @param observedSeconds 観測秒数
 * @param knownSeconds 既知秒数
 * @param unknownSeconds 不明秒数
 * @param categories 分類一覧
 * @param projects プロジェクト一覧
 * @param labels 表示名一覧
 * @param sourceSegments 取得元区間一覧
 * @param evidence 根拠
 * @param fineSessionIds fine会話識別子一覧
 */
public record TrendSummarySegment(
    Instant startedAt,
    Instant endedAt,
    Continuity continuity,
    String theme,
    long observedSeconds,
    long knownSeconds,
    long unknownSeconds,
    List<String> categories,
    List<String> projects,
    List<String> labels,
    List<SummarySegment> sourceSegments,
    List<ActivityRecord> evidence,
    List<String> fineSessionIds) {
  /** 作業状況の把握における継続性の選択肢。 */
  public enum Continuity {
    /** 継続。 */
    CONTINUOUS,
    /** 断続的な作業。 */
    INTERMITTENT,
    /** 複数の状態が混在する作業。 */
    MIXED
  }

  /** 受け取った情報からTrendSummarySegmentを作成する。 */
  public TrendSummarySegment {
    categories = List.copyOf(categories);
    projects = List.copyOf(projects);
    labels = List.copyOf(labels);
    sourceSegments = List.copyOf(sourceSegments);
    evidence = List.copyOf(evidence);
    fineSessionIds = List.copyOf(fineSessionIds);
  }

  /** 作業状況の把握で用いる未観測秒数を返す。 */
  public long unobservedSeconds() {
    return Math.max(0, Duration.between(startedAt, endedAt).getSeconds() - observedSeconds);
  }

  /**
   * 指定されたFine会話一覧を反映した情報を作成する。
   *
   * <p>Resolve exact child references after splitting a source projection that spanned several fine
   * sessions.
   */
  public TrendSummarySegment withFineSessions(List<ActivitySession> sessions) {
    var ids = new java.util.HashSet<>(evidence.stream().map(ActivityRecord::id).toList());
    var included =
        sessions.stream()
            .filter(
                s ->
                    s.startedAt().isBefore(endedAt)
                        && s.endedAt().isAfter(startedAt)
                        && s.recordIds().stream().anyMatch(ids::contains))
            .map(ActivitySession::id)
            .toList();
    return new TrendSummarySegment(
        startedAt,
        endedAt,
        continuity,
        theme,
        observedSeconds,
        knownSeconds,
        unknownSeconds,
        categories,
        projects,
        labels,
        sourceSegments,
        evidence,
        included);
  }
}
