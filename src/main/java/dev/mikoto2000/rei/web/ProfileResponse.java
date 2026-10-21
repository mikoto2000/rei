package dev.mikoto2000.rei.web;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Web APIによる操作で受け渡す利用者情報応答を表す。
 *
 * @param total 合計
 * @param first 最初
 * @param last 直近
 * @param countsByType 件数別種別
 * @param durationsByType 継続時間一覧別種別
 */
public record ProfileResponse(
    int total,
    String first,
    String last,
    Map<String, Long> countsByType,
    Map<String, DurationResponse> durationsByType) {
  /**
   * Web APIによる操作で受け渡す継続時間応答を表す。
   *
   * @param count 件数
   * @param totalMillis 合計ミリ秒数
   * @param minMillis 最小ミリ秒数
   * @param maxMillis 最大ミリ秒数
   * @param averageMillis 平均ミリ秒数
   */
  public record DurationResponse(
      long count, long totalMillis, long minMillis, long maxMillis, long averageMillis) {}

  /** Web APIによる操作で利用する情報を、受け取った形式から変換する。 */
  public static ProfileResponse from(
      dev.mikoto2000.rei.event.ProfileEventLogStore.ProfileSummary p) {
    return new ProfileResponse(
        p.total(),
        p.first() == null ? null : p.first().toString(),
        p.last() == null ? null : p.last().toString(),
        p.countsByType(),
        p.durationsByType().entrySet().stream()
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    e -> {
                      var d = e.getValue();
                      return new DurationResponse(
                          d.count(),
                          d.totalMillis(),
                          d.minMillis(),
                          d.maxMillis(),
                          d.averageMillis());
                    })));
  }
}
