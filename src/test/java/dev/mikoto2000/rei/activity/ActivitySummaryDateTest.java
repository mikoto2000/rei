package dev.mikoto2000.rei.activity;

import java.io.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivitySummaryDateTest {
  static final ZoneId TOKYO=ZoneId.of("Asia/Tokyo");
  static final Instant NOW=Instant.parse("2026-09-25T03:00:00Z");
  final ActivityStore store=mock(ActivityStore.class);
  final ActivityTimeline timeline=new ActivityTimeline(store,Clock.fixed(NOW,TOKYO));
  final StringWriter out=new StringWriter(),err=new StringWriter();
  int execute(String... args) {
    var command=new picocli.CommandLine(new ActivityCommand(timeline,mock(ActivityCapture.class),new ActivityProperties()));
    command.setOut(new PrintWriter(out));command.setErr(new PrintWriter(err));
    return command.execute(args);
  }
  @ParameterizedTest @ValueSource(strings={"", "today", "2026-09-25"})
  void todayStopsAtNow(String day) {
    assertEquals(0,day.isEmpty()?execute("summary"):execute("summary",day));
    verify(store).findRecordsBetween(Instant.parse("2026-09-24T15:00:00Z"),NOW);
    assertTrue(out.toString().contains("2026-09-25 の Activity は記録されていません。"));
  }
  @ParameterizedTest @ValueSource(strings={"yesterday","2026-09-24"})
  void pastDayUsesNextLocalMidnight(String day) {
    assertEquals(0,execute("summary",day));
    verify(store).findRecordsBetween(Instant.parse("2026-09-23T15:00:00Z"),Instant.parse("2026-09-24T15:00:00Z"));
  }
  @ParameterizedTest @ValueSource(strings={"2026-02-30","2026/09/25","foo","2026-9-25","+2026-09-25"})
  void invalidDateHasUsage(String day) {
    assertEquals(2,execute("summary",day));
    assertTrue(err.toString().contains("today、yesterday、または YYYY-MM-DD"));
    verifyNoInteractions(store);
  }
  @Test void futureDateIsExplicit() {
    assertEquals(2,execute("summary","2026-09-26"));
    assertTrue(err.toString().contains("2026-09-26 は未来の日付です。"));verifyNoInteractions(store);
  }
  @Test void extraArgumentsAreRejected() {
    assertEquals(2,execute("summary","today","foo"));verifyNoInteractions(store);
  }
  @ParameterizedTest @ValueSource(strings={"today","yesterday","2026-09-20"})
  void detailCommandsKeepFullDay(String day) {
    assertEquals(0,execute(day));
    var date=switch(day) {case "today" -> LocalDate.of(2026,9,25);case "yesterday" -> LocalDate.of(2026,9,24);default -> LocalDate.parse(day);};
    verify(store).findRecordsBetween(date.atStartOfDay(TOKYO).toInstant(),date.plusDays(1).atStartOfDay(TOKYO).toInstant());
  }
  @Test void midnightIsAnEmptyRange() {
    var midnight=new ActivityTimeline(store,Clock.fixed(Instant.parse("2026-09-24T15:00:00Z"),TOKYO));
    assertTrue(midnight.trendSummary("today").contains("2026-09-25 の Activity は記録されていません。"));
    verifyNoInteractions(store);
  }
}
