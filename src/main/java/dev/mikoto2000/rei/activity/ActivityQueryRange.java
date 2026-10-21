package dev.mikoto2000.rei.activity;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 作業状況の把握で受け渡す作業状況検索条件対象範囲を表す。
 *
 * <p>Half-open daily range. At today's midnight the range is empty.
 *
 * @param fromInclusive 起点Inclusive
 * @param toExclusive 終点排他
 */
public record ActivityQueryRange(Instant fromInclusive, Instant toExclusive) {
  /** 作業状況の把握で用いる対象日付を返す。 */
  public static ActivityQueryRange forDate(LocalDate date, Clock clock) {
    var now = clock.instant();
    var zone = clock.getZone();
    var today = now.atZone(zone).toLocalDate();
    if (date.isAfter(today)) throw new DateTimeException(date + " は未来の日付です。");
    return new ActivityQueryRange(
        date.atStartOfDay(zone).toInstant(),
        date.equals(today) ? now : date.plusDays(1).atStartOfDay(zone).toInstant());
  }
}
