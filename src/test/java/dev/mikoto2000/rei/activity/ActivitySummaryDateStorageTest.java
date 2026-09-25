package dev.mikoto2000.rei.activity;

import java.nio.file.Path;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ActivitySummaryDateStorageTest {
  @TempDir Path directory;
  ActivityRecord record(String instant,String project) {
    return new ActivityRecord(project+instant,Instant.parse(instant),60,List.of(),new ForegroundWindow("Terminal",1,project,"terminal"),
        new ActivityRecord.Inference("",List.of(ActivitySemanticTest.activity("coding","Terminal","",project))),.9,List.of(),.1,false);
  }
  @Test void summariesSeparateMidnightAndExcludeFutureEvidenceWithoutRewritingData() {
    var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+directory.resolve("activity.db"));
    var store=new SqliteActivityStore(ds,new SessionMergePolicy(Duration.ofSeconds(90),ActivitySummaryDateTest.TOKYO));
    var records=List.of(record("2026-09-24T14:58:00Z","previous-project"),record("2026-09-24T14:59:59.500Z","previous-project"),
        record("2026-09-24T15:00:00Z","today-project"),record("2026-09-25T02:59:30Z","ongoing-project"),
        record("2026-09-25T03:00:00Z","at-now-project"),record("2026-09-25T03:01:00Z","future-project"));
    records.forEach(store::append);
    var timeline=new ActivityTimeline(store,Clock.fixed(ActivitySummaryDateTest.NOW,ActivitySummaryDateTest.TOKYO));
    var fine=timeline.query("today");
    assertEquals(records.subList(0,2),timeline.summarySegments("yesterday").stream().flatMap(s->s.evidence().stream()).toList());
    assertEquals(records.subList(2,records.size()),timeline.summarySegments("today").stream().flatMap(s->s.evidence().stream()).toList());
    var yesterday=timeline.trendSummary("yesterday");var today=timeline.trendSummary("today");
    assertTrue(yesterday.contains("previous-project"));assertFalse(yesterday.contains("today-project"));
    assertTrue(today.startsWith("Activity Summary — 2026-09-25"));
    assertTrue(today.contains("today-project"));assertTrue(today.contains("11:59–12:00"));
    assertFalse(today.contains("previous-project"));assertFalse(today.contains("at-now-project"));assertFalse(today.contains("future-project"));
    assertEquals(today,timeline.trendSummary("2026-09-25"));
    assertEquals(yesterday,timeline.trendSummary("2026-09-24"));
    assertEquals(fine,timeline.query("today"));
    assertEquals(records,store.findRecordsBetween(Instant.parse("2026-09-23T15:00:00Z"),Instant.parse("2026-09-25T15:00:00Z")));
    assertEquals(timeline.summary("yesterday"),new ActivityTools(timeline).activitySummary("yesterday"));
  }
}
