package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityTrendQueryTest {
  @TempDir Path directory;
  @Test void trendReadPreservesFiveFineSessionsAndPhase31GapBoundaries() {
    var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+directory.resolve("activity.db"));
    var store=new SqliteActivityStore(ds,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC));
    var records=List.of(ActivityTrendTest.unknown(0),ActivityPhase31Test.web(11,"social","X"),ActivityTrendTest.unknown(13),ActivityPhase31Test.web(28,"social","X"),ActivityTrendTest.unknown(30));
    records.forEach(store::append);var timeline=new ActivityTimeline(store,Clock.fixed(ActivitySemanticTest.START,ZoneOffset.UTC));
    var before=timeline.query("today");var safe=timeline.summarySegments("today");assertEquals(5,before.size());
    var trends=timeline.trendSegments("today");assertEquals(1,trends.size());assertEquals(5,trends.getFirst().fineSessionIds().size());
    assertEquals(records,trends.getFirst().evidence());assertEquals(before,timeline.query("today"));assertEquals(safe,timeline.summarySegments("today"));
    var reopened=new SqliteActivityStore(ds,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC));
    assertEquals(before,reopened.findBetween(ActivitySemanticTest.START,ActivitySemanticTest.START.plusSeconds(31*60)));
  }
  @Test void onlySummarySlashActionUsesTrendFormatter() {
    var timeline=mock(ActivityTimeline.class);when(timeline.trendSummary("today")).thenReturn("trend");when(timeline.summary("today")).thenReturn("detail");
    var command=new picocli.CommandLine(new ActivityCommand(timeline,mock(ActivityCapture.class),new ActivityProperties()));
    var output=new java.io.StringWriter();command.setOut(new java.io.PrintWriter(output));
    assertEquals(0,command.execute("summary"));assertEquals("trend",output.toString().strip());verify(timeline,never()).summary(anyString());
    assertEquals(0,command.execute("today"));verify(timeline).summary("today");
  }
  @Test void dateAndNoDataQueriesRemainSafe() {
    var store=mock(ActivityStore.class);var timeline=new ActivityTimeline(store,Clock.fixed(ActivitySemanticTest.START,ZoneOffset.UTC));
    assertTrue(timeline.trendSegments("yesterday").isEmpty());assertTrue(timeline.trendSummary("today").contains("2026-09-23 の Activity は記録されていません。"));
    assertThrows(java.time.DateTimeException.class,()->timeline.trendSummary("invalid"));
  }
  @Test void splitProjectionRetainsExactFineReferencesAndStoredRecords() {
    var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+directory.resolve("split.db"));
    var store=new SqliteActivityStore(ds,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC));
    var records=new ArrayList<ActivityRecord>();
    for(int i=0;i<55;i++) records.add(ActivitySemanticTest.record(i,"Terminal","rei",
        ActivitySemanticTest.activity(i<30?"development":"documentation","Terminal","","rei")));
    records.forEach(store::append);var timeline=new ActivityTimeline(store,Clock.fixed(ActivitySemanticTest.START,ZoneOffset.UTC));
    var before=timeline.query("today");var sources=timeline.summarySegments("today");
    var result=timeline.trendSegments("today");assertEquals(2,result.size());
    assertEquals(before,timeline.query("today"));assertEquals(sources,timeline.summarySegments("today"));
    assertEquals(records,result.stream().flatMap(s->s.evidence().stream()).toList());
    for(var s:result) {
      var ids=s.evidence().stream().map(ActivityRecord::id).toList();
      assertEquals(before.stream().filter(f->f.recordIds().stream().anyMatch(ids::contains)).map(ActivitySession::id).toList(),s.fineSessionIds());
    }
    var reopened=new SqliteActivityStore(ds,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC));
    assertEquals(records,reopened.findRecordsBetween(ActivitySemanticTest.START,ActivitySemanticTest.START.plusSeconds(3300)));
  }
}
