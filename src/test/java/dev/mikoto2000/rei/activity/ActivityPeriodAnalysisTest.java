package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActivityPeriodAnalysisTest {
  private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"),ZoneOffset.UTC);
  private static ActivityRecord record(String id,String time,long seconds) {
    var base=ActivitySemanticTest.dev(0,"Terminal","X");
    return new ActivityRecord(id,Instant.parse(time),seconds,base.observations(),base.foreground(),base.inference(),.8,List.of(),0,false,"one");
  }
  @Test void calendarWeekStartsMondayAndCurrentRangeStopsAtFrozenNow() {
    var service=new ActivityPeriodAnalysis(CLOCK,.5);
    var r=service.range(ActivityPeriodAnalysis.Period.WEEK,LocalDate.of(2026,10,4));
    assertEquals(Instant.parse("2026-09-28T00:00:00Z"),r.fromInclusive());
    assertEquals(CLOCK.instant(),r.toExclusive());
    assertTrue(r.partial());
    assertThrows(DateTimeException.class,()->service.range(ActivityPeriodAnalysis.Period.MONTH,LocalDate.of(2026,10,5)));
  }
  @Test void overlapDuplicateAndMidnightDoNotInflateObservation() {
    var service=new ActivityPeriodAnalysis(CLOCK,.5);
    var r=service.range(ActivityPeriodAnalysis.Period.MONTH,LocalDate.of(2026,9,23));
    var a=record("a","2026-09-23T23:59:00Z",600);
    var b=record("b","2026-09-23T23:59:30Z",600);
    var result=service.aggregate(r,List.of(a,b,a));
    assertEquals(60,result.observedSeconds());
    assertEquals(60,result.categorySeconds().get("development"));
    assertEquals(60,result.projectCandidateSeconds().get("rei"));
    assertEquals(60,result.hourSeconds().get(23));
    assertEquals(Duration.between(r.fromInclusive(),r.toExclusive()).getSeconds()-60,result.unobservedSeconds());
  }
  @Test void daylightSavingMonthUsesCalendarBoundaryAndRealSeconds() {
    var service=new ActivityPeriodAnalysis(Clock.fixed(Instant.parse("2026-12-02T00:00:00Z"),ZoneId.of("America/New_York")),.5);
    var r=service.range(ActivityPeriodAnalysis.Period.MONTH,LocalDate.of(2026,11,5));
    assertEquals(30*86400L+3600,Duration.between(r.fromInclusive(),r.toExclusive()).getSeconds());
  }
  @Test void emptyAndUnknownRemainUnknownWithoutProductivityScore() {
    var service=new ActivityPeriodAnalysis(CLOCK,.5);
    var r=service.range(ActivityPeriodAnalysis.Period.WEEK,LocalDate.of(2026,9,23));
    var raw=record("a","2026-09-23T09:00:00Z",60);
    var unknown=new ActivityRecord(raw.id(),raw.capturedAt(),60,List.of(),null,raw.inference(),.1,List.of(),0,false,"one");
    var result=service.aggregate(r,List.of(unknown));
    assertEquals(Map.of("unknown",60L),result.categorySeconds());
    assertTrue(result.projectCandidateSeconds().isEmpty());
    String text=service.format(result,service.aggregate(service.previous(r),List.of()));
    assertTrue(text.contains("推定分類"));assertTrue(text.contains("生産性スコア: 未測定"));
    assertTrue(text.contains("観測なし"));assertFalse(text.contains("NaN"));
  }
  @Test void previousMonthHandlesUnequalLengthsAndFormatsPartialComparison() {
    var service=new ActivityPeriodAnalysis(CLOCK,.5);
    var r=service.range(ActivityPeriodAnalysis.Period.MONTH,LocalDate.of(2026,10,4));
    var previous=service.previous(r);
    assertEquals(Instant.parse("2026-09-01T00:00:00Z"),previous.fromInclusive());
    assertEquals(Instant.parse("2026-10-01T00:00:00Z"),previous.toExclusive());
    assertFalse(previous.partial());
    String text=service.format(service.aggregate(r,List.of()),service.aggregate(previous,List.of()));
    assertTrue(text.contains("途中期間"));assertTrue(text.contains("成果・集中・中断の実測ではありません"));
  }
  @Test void shellQueriesTwoOwnedCalendarRangesWithoutDailyWriterOrMutation() {
    var store=org.mockito.Mockito.mock(ActivityStore.class);
    var timeline=new ActivityTimeline(store,CLOCK);
    var out=new java.io.StringWriter();
    var shell=new picocli.CommandLine(new ActivityCommand(timeline,null,null));
    shell.setOut(new java.io.PrintWriter(out));
    assertEquals(0,shell.execute("weekly","2026-09-23"));
    org.mockito.Mockito.verify(store).findRecordsBetween(Instant.parse("2026-09-21T00:00:00Z"),Instant.parse("2026-09-28T00:00:00Z"));
    org.mockito.Mockito.verify(store).findRecordsBetween(Instant.parse("2026-09-14T00:00:00Z"),Instant.parse("2026-09-21T00:00:00Z"));
    org.mockito.Mockito.verifyNoMoreInteractions(store);
    assertTrue(out.toString().contains("週次 Activity 分析"));
  }
  @Test void monthlyShellRejectsFutureAndHandlesEmptyCurrentMonth() {
    var store=org.mockito.Mockito.mock(ActivityStore.class);
    var timeline=new ActivityTimeline(store,Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"),ZoneOffset.UTC));
    var shell=new picocli.CommandLine(new ActivityCommand(timeline,null,null));
    var out=new java.io.StringWriter();shell.setOut(new java.io.PrintWriter(out));shell.setErr(new java.io.PrintWriter(out));
    assertEquals(2,shell.execute("monthly","2026-10-05"));
    org.mockito.Mockito.verifyNoInteractions(store);
    assertEquals(0,shell.execute("monthly"));
    org.mockito.Mockito.verify(store).findRecordsBetween(Instant.parse("2026-09-01T00:00:00Z"),Instant.parse("2026-10-01T00:00:00Z"));
    org.mockito.Mockito.verifyNoMoreInteractions(store);
    assertTrue(out.toString().contains("月次 Activity 分析"));
  }
}
