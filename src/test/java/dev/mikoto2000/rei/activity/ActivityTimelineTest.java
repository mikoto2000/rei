package dev.mikoto2000.rei.activity;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class ActivityTimelineTest {
  @TempDir Path directory;
  SqliteActivityStore store;
  ActivityTimeline timeline;
  @BeforeEach void setup() {
    var ds=new org.sqlite.SQLiteDataSource(); ds.setUrl("jdbc:sqlite:"+directory.resolve("activity.db"));
    store=new SqliteActivityStore(ds,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC));
    timeline=new ActivityTimeline(store,Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"),ZoneOffset.UTC));
    store.append(ActivityPolicyTest.record("2026-09-22T10:00:00Z","coding"));
    store.append(ActivityPolicyTest.record("2026-09-22T10:01:00Z","coding"));
    store.append(ActivityPolicyTest.record("2026-09-23T08:00:00Z","research"));
  }
  @Test void today() { assertEquals("visible research",timeline.query("today").getFirst().inference().summary()); }
  @Test void yesterday() { assertEquals(2,timeline.query("yesterday").getFirst().recordIds().size()); }
  @Test void specifiedDate() { assertEquals(1,timeline.findByDate(LocalDate.parse("2026-09-22")).size()); }
  @Test void rangeClipsSession() { var s=timeline.findBetween(Instant.parse("2026-09-22T10:00:30Z"),Instant.parse("2026-09-22T10:01:30Z")).getFirst(); assertEquals(60,Duration.between(s.startedAt(),s.endedAt()).getSeconds()); }
  @Test void rangeIsHalfOpen() { assertTrue(timeline.findBetween(Instant.parse("2026-09-22T10:02:00Z"),Instant.parse("2026-09-22T11:00:00Z")).isEmpty()); }
  @Test void invalidRangeRejected() { assertThrows(IllegalArgumentException.class,()->timeline.findBetween(Instant.MAX,Instant.MIN)); }
  @Test void dataSurvivesReopen() { var ds=new org.sqlite.SQLiteDataSource(); ds.setUrl("jdbc:sqlite:"+directory.resolve("activity.db")); var reopened=new SqliteActivityStore(ds,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC)); assertEquals(2,reopened.findBetween(Instant.parse("2026-09-22T00:00:00Z"),Instant.parse("2026-09-24T00:00:00Z")).size()); }
  @Test void screenshotRetentionKeepsRecordsAndSessions() throws Exception {
    var screenshots=new FileScreenshotStore(directory.resolve("screenshots"));
    var old=screenshots.save("00000000-0000-0000-0000-000000000001",Instant.parse("2026-09-19T10:00:00Z"),ActivityCaptureTest.screen(20));
    var recent=screenshots.save("00000000-0000-0000-0000-000000000002",Instant.parse("2026-09-23T10:00:00Z"),ActivityCaptureTest.screen(20));
    screenshots.cleanup(Instant.parse("2026-09-20T10:00:00Z"));
    assertFalse(Files.exists(directory.resolve("screenshots").resolve(old.getFirst())));
    assertTrue(Files.exists(directory.resolve("screenshots").resolve(recent.getFirst())));
    assertEquals(2,timeline.query("yesterday").getFirst().recordIds().size());
  }
  @Test void summaryUsesSemanticProjection() { String summary=timeline.summary("yesterday"); assertTrue(summary.contains("rei関連の開発")); assertFalse(summary.contains("visible coding")); assertTrue(summary.contains("10:00")); }
  @Test void daylightSavingDateHasCorrectBounds() {
    var mock=org.mockito.Mockito.mock(ActivityStore.class);
    var t=new ActivityTimeline(mock,Clock.fixed(Instant.parse("2026-03-08T12:00:00Z"),ZoneId.of("America/New_York")));
    t.findByDate(LocalDate.parse("2026-03-08"));
    org.mockito.Mockito.verify(mock).findBetween(Instant.parse("2026-03-08T05:00:00Z"),Instant.parse("2026-03-09T04:00:00Z"));
  }
}
