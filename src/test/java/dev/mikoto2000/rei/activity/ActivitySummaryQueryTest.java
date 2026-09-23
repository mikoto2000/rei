package dev.mikoto2000.rei.activity;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ActivitySummaryQueryTest {
  @TempDir Path directory;
  SqliteActivityStore store;
  ActivityTimeline timeline;
  @BeforeEach void setup() {
    var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+directory.resolve("activity.db"));
    store=new SqliteActivityStore(ds,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC));
    timeline=new ActivityTimeline(store,Clock.fixed(ActivitySemanticTest.START,ZoneOffset.UTC));
  }
  @Test void compressedSummaryRetainsFineSessionsAndRawEvidenceAcrossReopen() {
    var records=java.util.stream.IntStream.range(0,20).mapToObj(i->ActivitySemanticTest.dev(i,i%2==0?"Terminal":"GVIM","X")).toList();
    records.forEach(store::append);
    assertEquals(20,timeline.query("today").size());
    var segments=timeline.summarySegments("today");
    assertEquals(1,segments.size());assertEquals(20,segments.getFirst().fineSessionIds().size());
    assertEquals(records,segments.getFirst().evidence());
    assertFalse(timeline.summary("today").contains("積極的に操作"));
    var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+directory.resolve("activity.db"));
    var reopened=new SqliteActivityStore(ds,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC));
    assertEquals(records,reopened.findRecordsBetween(ActivitySemanticTest.START,ActivitySemanticTest.START.plusSeconds(1200)));
    assertEquals(20,reopened.findBetween(ActivitySemanticTest.START,ActivitySemanticTest.START.plusSeconds(1200)).size());
  }
  @Test void rangeClipsWithoutLosingEvidenceOrInflatingGap() {
    store.append(ActivitySemanticTest.dev(0,"Terminal","X"));store.append(ActivitySemanticTest.dev(4,"GVIM","X"));
    var segments=timeline.summaryBetween(ActivitySemanticTest.START.plusSeconds(30),ActivitySemanticTest.START.plusSeconds(270));
    assertEquals(1,segments.size());var s=segments.getFirst();
    assertEquals(60,s.observedSeconds());assertEquals(180,s.unobservedSeconds());
    assertEquals(2,s.evidence().size());assertEquals(ActivitySemanticTest.START.plusSeconds(30),s.startedAt());
    assertTrue(timeline.summaryBetween(ActivitySemanticTest.START.plusSeconds(60),ActivitySemanticTest.START.plusSeconds(240)).isEmpty());
  }
  @Test void existingPersistedSessionJsonNeedsNoMigration() throws Exception {
    store.append(ActivitySemanticTest.dev(0,"Terminal","X"));
    assertEquals(1,timeline.summarySegments("2026-09-23").size());
    assertTrue(timeline.summarySegments("yesterday").isEmpty());
    assertThrows(IllegalArgumentException.class,()->timeline.summaryBetween(ActivitySemanticTest.START,ActivitySemanticTest.START.plus(Duration.ofDays(32))));
  }
  @Test void themeGroupingPreservesOriginalCategoriesAndEvidence() {
    var records=List.of(ActivityPhase31Test.web(0,"social","Twitter"),ActivityPhase31Test.web(1,"shopping","Amazon"),ActivityPhase31Test.web(2,"social","X (Twitter)"));
    records.forEach(store::append);var fineBefore=timeline.query("today");
    var result=timeline.summarySegments("today");assertEquals(1,result.size());assertEquals("web-browsing",result.getFirst().theme());
    assertEquals(fineBefore,timeline.query("today"));assertEquals(records,result.getFirst().evidence());
    assertEquals(3,result.getFirst().fineSessionIds().size());
    assertTrue(result.getFirst().primaryCategories().containsAll(List.of("social","shopping")));
  }
}
