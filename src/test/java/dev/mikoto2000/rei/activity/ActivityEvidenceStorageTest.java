package dev.mikoto2000.rei.activity;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityEvidenceStorageTest {
  @TempDir Path directory;
  @Test void evidenceAndEnrichmentRoundTripWithoutAddingTimeAndRemainCompatibleWithBehaviorAndSummary() throws Exception {
    var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+directory.resolve("evidence.db"));
    var store=new SqliteActivityStore(ds,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC));
    var p=new ActivityProperties();p.setEnabled(true);var observer=mock(DesktopActivityObserver.class);var time=new ActivityChangeScopeTest.Time();var start=time.instant();
    var tasks=new ArrayList<Runnable>();
    when(observer.foreground()).thenReturn(ActivityEvidenceClassifierTest.window("Firefox","ホーム / X"));
    when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(10,20));
    ActivityExtractor extractor=(screen,fg)->new ActivityExtractor.Result(new ActivityRecord.Inference("X visible",List.of(new ActivityRecord.Activity("primary","social","Firefox","X","",""))),.9);
    var capture=new ActivityCapture(p,observer,extractor,store,new FileScreenshotStore(directory.resolve("screenshots")),time,tasks::add,Runnable::run);
    capture.tick();time.advance(60);
    when(observer.foreground()).thenReturn(ActivityEvidenceClassifierTest.window("Firefox","Mozilla Firefox"));capture.tick();time.advance(60);
    var before=store.findRecordsBetween(start,time.instant());assertEquals(2,before.size());assertEquals("PROVISIONAL",before.getLast().detection().status());
    tasks.getFirst().run();
    var records=store.findRecordsBetween(start,time.instant());assertEquals(2,records.size());assertEquals(before.getLast().id(),records.getLast().id());
    assertEquals(120,records.stream().mapToLong(ActivityRecord::durationEstimate).sum());assertTrue(records.getLast().detection().visionUsed());
    assertFalse(records.getFirst().detection().visionUsed());assertNotNull(records.getFirst().detection().evidence());
    var evaluator=new dev.mikoto2000.rei.activity.behavior.BehaviorEvaluator(new dev.mikoto2000.rei.activity.behavior.BehaviorProperties(),.5);
    var assessed=evaluator.evaluate(store.findBetween(start,time.instant()),records,time.instant());
    assertEquals(120,assessed.windows().getFirst().observedSeconds());assertEquals(120,assessed.windows().getFirst().entertainmentObservedSeconds());
    var timeline=new ActivityTimeline(store,time);assertFalse(timeline.summarySegments("today").isEmpty());
    assertTrue(timeline.summary("today").contains("SNS"));assertFalse(java.nio.file.Files.exists(directory.resolve("screenshots")));
  }
  @Test void legacyJsonWithoutDetectionStillLoads() throws Exception {
    var mapper=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    var node=mapper.valueToTree(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social"));
    ((com.fasterxml.jackson.databind.node.ObjectNode)node).remove("detection");
    assertNull(mapper.treeToValue(node,ActivityRecord.class).detection());
  }
  @Test void agentSourceKeepsOnlyBoundedRecentKindsAndProjectIds() {
    var source=new ActivityAgentEvidenceSource(null);var at=Instant.now();
    for(int i=0;i<20;i++)source.onEvent(new dev.mikoto2000.rei.event.AgentEvent("e"+i,i,at,dev.mikoto2000.rei.event.AgentEventType.TOOL_COMPLETED,1,"s","t","r","c",null,
        new dev.mikoto2000.rei.event.ToolCompletedPayload("tool","runCommand",1,"private result",null,null,null,null),"p"));
    var evidence=source.collect(at);assertEquals(16,evidence.events().size());assertEquals("SHELL",evidence.events().getFirst().kind());assertFalse(evidence.toString().contains("private"));
    assertTrue(source.collect(at.plusSeconds(121)).events().isEmpty());source.close();
  }
}
