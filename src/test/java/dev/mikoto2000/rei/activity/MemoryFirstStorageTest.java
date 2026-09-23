package dev.mikoto2000.rei.activity;

import java.nio.file.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryFirstStorageTest {
  @TempDir Path directory;

  @ParameterizedTest @ValueSource(booleans={false,true})
  void recordSessionSummaryAndRetentionWorkWithOptionalEvidence(boolean keep) throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);p.getDetection().setMode(ActivityProperties.DetectionMode.VISION_FIRST);p.getDetection().setBackgroundFullScreenEnabled(true);p.setKeepScreenshots(keep);
    var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+directory.resolve("activity.db"));
    var store=new SqliteActivityStore(ds,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC));
    var screenshots=new FileScreenshotStore(directory.resolve("screenshots"));
    var at=Instant.parse("2026-09-22T10:00:00Z");
    var clock=Clock.fixed(at,ZoneOffset.UTC);
    var observer=mock(DesktopActivityObserver.class);
    when(observer.foreground()).thenReturn(new ForegroundWindow("idea",1,"rei","1"));
    when(observer.capture()).thenReturn(ActivityCaptureTest.screen(20));
    ActivityExtractor extractor=(screen,foreground)->new ActivityOutputParser().parse(ActivityExtractionTest.VALID,List.of("m1","m2"));
    new ActivityCapture(p,observer,extractor,store,screenshots,clock).tick();
    var timeline=new ActivityTimeline(store,clock);
    assertEquals(1,timeline.query("today").size());assertTrue(timeline.summary("today").contains("X・YouTube"));
    assertEquals("X and YouTube are visible",timeline.query("today").getFirst().inference().summary());
    try(var c=ds.getConnection();var s=c.createStatement()) {
      for(var table:List.of("activity_records","activity_sessions")) {
        try(var result=s.executeQuery("SELECT payload FROM "+table)) {
          assertTrue(result.next());String json=result.getString(1);
          assertFalse(json.contains("data:image/"));assertFalse(json.contains("iVBOR"));assertTrue(json.length()<10000);
          if(table.equals("activity_records")) {
            var record=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).readValue(json,ActivityRecord.class);
            assertEquals(keep?2:0,record.screenshotReferences().size());
          }
        }
      }
    }
    assertEquals(keep,Files.exists(directory.resolve("screenshots")));
    // Opting out later still cleans up previously retained evidence; journal data survives.
    p.setKeepScreenshots(false);p.setEnabled(false);
    new ActivityCapture(p,observer,extractor,store,screenshots,Clock.fixed(at.plus(Duration.ofDays(4)),ZoneOffset.UTC)).tick();
    if(keep)try(var files=Files.list(directory.resolve("screenshots"))) {assertEquals(0,files.count());}
    assertEquals(1,timeline.query("today").size());assertTrue(timeline.summary("today").contains("X・YouTube"));
  }

  @Test void failedExtractionEvidenceUsesTheSameRetention() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);p.getDetection().setMode(ActivityProperties.DetectionMode.VISION_FIRST);p.getDetection().setBackgroundFullScreenEnabled(true);p.setKeepOnExtractionFailure(true);
    var screenshots=new FileScreenshotStore(directory.resolve("screenshots"));
    var observer=mock(DesktopActivityObserver.class);var store=mock(ActivityStore.class);
    when(observer.foreground()).thenReturn(new ForegroundWindow("idea",1,"rei","1"));when(observer.capture()).thenReturn(ActivityCaptureTest.screen(20));
    var at=Instant.parse("2026-09-22T10:00:00Z");
    new ActivityCapture(p,observer,(screen,foreground)->{throw new java.io.IOException();},store,screenshots,Clock.fixed(at,ZoneOffset.UTC)).tick();
    try(var files=Files.list(directory.resolve("screenshots"))) {assertEquals(2,files.count());}
    screenshots.cleanup(at.plus(Duration.ofDays(4)));
    try(var files=Files.list(directory.resolve("screenshots"))) {assertEquals(0,files.count());}
    verifyNoInteractions(store);
  }
}
