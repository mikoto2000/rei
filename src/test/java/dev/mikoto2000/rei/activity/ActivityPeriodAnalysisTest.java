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
  @Test void userCriteriaScoreTracksDaysAndUnknownTimeIsExcluded() {
    var analysis=new ActivityPeriodAnalysis(CLOCK,.5,new ProjectNameNormalizer(Map.of()),Set.of("development"));
    var range=analysis.range(ActivityPeriodAnalysis.Period.WEEK,LocalDate.of(2026,9,23));
    var dev=record("dev","2026-09-23T09:00:00Z",600);
    var raw=record("unknown","2026-09-23T10:00:00Z",600);
    var unknown=new ActivityRecord(raw.id(),raw.capturedAt(),600,List.of(),null,raw.inference(),.1,List.of(),0,false,"one");
    var result=analysis.aggregate(range,List.of(dev,unknown));
    assertEquals(100.0,result.metrics().score());assertEquals(600,result.metrics().classifiedSeconds());
    assertEquals(Map.of(LocalDate.of(2026,9,23),100.0),result.metrics().dayScores());
    var text=analysis.format(result,analysis.aggregate(analysis.previous(range),List.of()));
    assertTrue(text.contains("基準適合指数"));assertTrue(text.contains("development"));assertFalse(text.contains("NaN"));
    var noCriteria=new ActivityPeriodAnalysis(CLOCK,.5).aggregate(range,List.of(dev));assertNull(noCriteria.metrics().score());
  }
  @Test void focusCandidatesUseObservedSecondsAndGapsDoNotCountAsInterruptions() {
    var analysis=new ActivityPeriodAnalysis(CLOCK,.5,new ProjectNameNormalizer(Map.of()),Set.of("development"));
    var range=analysis.range(ActivityPeriodAnalysis.Period.WEEK,LocalDate.of(2026,9,23));
    var a=record("a","2026-09-23T09:00:00Z",900);var b=record("b","2026-09-23T09:15:00Z",900);
    var c=record("c","2026-09-23T12:00:00Z",600);
    var result=analysis.aggregate(range,List.of(a,b,c,a));
    assertEquals(1,result.metrics().focusCandidates());assertEquals(1800,result.metrics().focusSeconds());
    assertEquals(1800,result.metrics().longestStableSeconds());assertEquals(0,result.metrics().interruptionCandidates());
  }

  @Test void classifiedSharesAndAdjacentThemeSwitchesAreComparedWithoutScoringGaps() {
    var analysis=new ActivityPeriodAnalysis(CLOCK,.5,new ProjectNameNormalizer(Map.of()),Set.of("development"));
    var range=analysis.range(ActivityPeriodAnalysis.Period.WEEK,LocalDate.of(2026,9,23));
    var dev=record("dev","2026-09-23T09:00:00Z",600);var base=record("research","2026-09-23T09:10:00Z",600);
    var candidate=base.inference().activities().getFirst();
    var research=new ActivityRecord(base.id(),base.capturedAt(),600,base.observations(),base.foreground(),
        new ActivityRecord.Inference("",List.of(new ActivityRecord.Activity(candidate.monitor(),"research",candidate.application(),candidate.service(),candidate.contentTitle(),candidate.projectCandidate()))),.8,List.of(),0,false,"one");
    var result=analysis.aggregate(range,List.of(dev,research));assertEquals(50.0,result.metrics().score());assertEquals(1,result.metrics().interruptionCandidates());
    var previous=analysis.aggregate(analysis.previous(range),List.of(record("old","2026-09-16T09:00:00Z",600)));
    assertTrue(analysis.format(result,previous).contains("-50.0ポイント"));
    var changedSeries=new ActivityRecord(research.id(),research.capturedAt(),600,research.observations(),research.foreground(),research.inference(),.8,List.of(),0,false,"two");
    assertEquals(0,analysis.aggregate(range,List.of(dev,changedSeries)).metrics().interruptionCandidates());
    assertThrows(IllegalArgumentException.class,()->new ActivityPeriodAnalysis(CLOCK,.5,new ProjectNameNormalizer(Map.of()),Set.of("unknown")));
  }
  @Test @org.junit.jupiter.api.Tag("integration")
  void savedUserCategoriesReachWeeklyAndMonthlyReportsAfterRestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
    var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("criteria.db"));
    var settings=new SqlitePeriodCoachingStore(source);settings.configure(new PeriodCoaching.Settings(false,Set.of("development"),.6,120,.1,.25,7));
    var reopened=new SqlitePeriodCoachingStore(source);var store=org.mockito.Mockito.mock(ActivityStore.class);
    org.mockito.Mockito.when(store.findRecordsBetween(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(List.of(record("a","2026-09-23T09:00:00Z",600)));
    var policy=new SemanticSessionPolicy(Duration.ofSeconds(120),Duration.ofSeconds(300),Duration.ofSeconds(120),.5,ZoneOffset.UTC);
    var timeline=new ActivityTimeline(store,CLOCK,policy,DailySummaryService.local(),reopened);
    assertTrue(timeline.periodAnalysis(ActivityPeriodAnalysis.Period.WEEK,"2026-09-23").contains("100.0 / 指定分類=[development]"));
    assertTrue(timeline.periodAnalysis(ActivityPeriodAnalysis.Period.MONTH,"2026-09-23").contains("100.0 / 指定分類=[development]"));
    assertFalse(reopened.load().settings().enabled());
  }

}
