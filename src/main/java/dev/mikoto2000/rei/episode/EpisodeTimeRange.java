package dev.mikoto2000.rei.episode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 過去の経験の活用で受け渡す経験の記録時刻対象範囲を表す。
 *
 * @param from 起点
 * @param until 終了日時
 * @param exclusiveEnd 排他終了
 */
public record EpisodeTimeRange(Instant from, Instant until, boolean exclusiveEnd) {
  /** 過去の経験の活用で受け渡す情報を、指定された内容から作成する。 */
  public static EpisodeTimeRange of(String since, String until) {
    Instant start = since == null || since.isBlank() ? null : parse(since, false);
    boolean dateEnd = until != null && until.matches("\\d{4}-\\d{2}-\\d{2}");
    Instant end = until == null || until.isBlank() ? null : parse(until, dateEnd);
    if (start != null && end != null && end.isBefore(start))
      throw new IllegalArgumentException("Inverted episode time range");
    return new EpisodeTimeRange(start, end, dateEnd);
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  private static Instant parse(String value, boolean nextDay) {
    if (value.matches("\\d{4}-\\d{2}-\\d{2}"))
      return LocalDate.parse(value)
          .plusDays(nextDay ? 1 : 0)
          .atStartOfDay(ZoneOffset.UTC)
          .toInstant();
    return OffsetDateTime.parse(value).toInstant();
  }

  /** 過去の経験の活用において、含有に該当するか判定する。 */
  public boolean includes(Instant time) {
    return (from == null || !time.isBefore(from))
        && (until == null || (exclusiveEnd ? time.isBefore(until) : !time.isAfter(until)));
  }

  /** 過去の経験の活用で用いるsortableを返す。 */
  public static String sortable(Instant value) {
    return new java.time.format.DateTimeFormatterBuilder()
        .appendInstant(9)
        .toFormatter()
        .format(value);
  }
}
