package dev.mikoto2000.rei.activity;

import java.time.*;

/** Half-open daily range. At today's midnight the range is empty. */
public record ActivityQueryRange(Instant fromInclusive,Instant toExclusive) {
  public static ActivityQueryRange forDate(LocalDate date,Clock clock) {
    var now=clock.instant();var zone=clock.getZone();var today=now.atZone(zone).toLocalDate();
    if(date.isAfter(today)) throw new DateTimeException(date+" は未来の日付です。");
    return new ActivityQueryRange(date.atStartOfDay(zone).toInstant(),
        date.equals(today)?now:date.plusDays(1).atStartOfDay(zone).toInstant());
  }
}
