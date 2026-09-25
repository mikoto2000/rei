package dev.mikoto2000.rei.activity;

import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class ActivityDateArgumentResolverTest {
  @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"today","2026-09-25"})
  void defaultsAndExplicitTodayResolveEqually(String input) {
    var resolver=new ActivityDateArgumentResolver(Clock.fixed(ActivitySummaryDateTest.NOW,ActivitySummaryDateTest.TOKYO));
    assertEquals(LocalDate.of(2026,9,25),resolver.resolve(input));
  }
  @Test void sameInstantUsesJournalZone() {
    var instant=Instant.parse("2026-09-24T16:00:00Z");
    var utc=new ActivityDateArgumentResolver(Clock.fixed(instant,ZoneOffset.UTC));
    var tokyo=new ActivityDateArgumentResolver(Clock.fixed(instant,ActivitySummaryDateTest.TOKYO));
    assertEquals(LocalDate.of(2026,9,24),utc.resolve("today"));
    assertEquals(LocalDate.of(2026,9,25),tokyo.resolve("today"));
    assertEquals(LocalDate.of(2026,9,24),tokyo.resolve("yesterday"));
    assertThrows(DateTimeException.class,()->utc.resolve("2026-09-25"));
    assertEquals(LocalDate.of(2026,9,25),tokyo.resolve("2026-09-25"));
  }
  @ParameterizedTest @CsvSource({"2026-03-08,23","2026-11-01,25"})
  void pastDaysFollowDst(String day,long hours) {
    var range=ActivityQueryRange.forDate(LocalDate.parse(day),Clock.fixed(Instant.parse("2026-12-01T12:00:00Z"),ZoneId.of("America/New_York")));
    assertEquals(Duration.ofHours(hours),Duration.between(range.fromInclusive(),range.toExclusive()));
  }
  @Test void rangeBuilderRejectsFutureDate() {
    assertThrows(DateTimeException.class,()->ActivityQueryRange.forDate(LocalDate.of(2026,9,26),Clock.fixed(ActivitySummaryDateTest.NOW,ActivitySummaryDateTest.TOKYO)));
  }
}
