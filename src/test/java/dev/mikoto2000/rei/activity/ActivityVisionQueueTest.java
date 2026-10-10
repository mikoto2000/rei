package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityVisionQueueTest {
  static class Fixture {
    final ActivityProperties p=new ActivityProperties();
    final ActivityChangeScopeTest.Time time=new ActivityChangeScopeTest.Time();
    final DesktopActivityObserver observer=mock(DesktopActivityObserver.class);
    final ActivityExtractor extractor=mock(ActivityExtractor.class);
    final ActivityStore store=mock(ActivityStore.class);
    final List<Runnable> tasks=new ArrayList<>();
    final ActivityEvidencePipeline pipeline;
    ForegroundWindow foreground=new ForegroundWindow("Firefox",1,"Mozilla Firefox","1");
    Fixture() throws Exception {this(List.of());}
    Fixture(List<ActivityEvidenceSource> sources) throws Exception {
      p.setEnabled(true);p.getDetection().setForegroundCrop(false);
      when(observer.foreground()).thenAnswer(i->foreground);
      when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(20,20));
      when(extractor.extract(any(),any())).thenReturn(new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.9));
      pipeline=new ActivityEvidencePipeline(p,observer,extractor,store,mock(ScreenshotStore.class),time,tasks::add,Runnable::run,sources);
    }
    void run(){tasks.removeFirst().run();}
    void change(String title){foreground=new ForegroundWindow("Firefox",1,"Mozilla Firefox "+title,"1");}
  }
  @Test void duplicateMetadataAvoidsImagesAndApiUntilRefresh() throws Exception {
    var f=new Fixture();f.pipeline.tick();f.run();f.time.advance(15);f.pipeline.tick();
    verify(f.store,times(2)).append(any());verify(f.observer).capture();verify(f.extractor).extract(any(),any());
    f.time.advance(285);f.pipeline.tick();f.run();verify(f.extractor,times(2)).extract(any(),any());
    assertEquals(3,f.pipeline.queueMetrics().generated());assertEquals(1,f.pipeline.queueMetrics().duplicateSkipped());
  }
  @Test void cooldownRetainsOnlyLatestAndKeepsEveryOsObservation() throws Exception {
    var f=new Fixture();f.pipeline.tick();f.run();f.time.advance(15);f.change("B");f.pipeline.tick();
    f.time.advance(5);f.change("C");f.pipeline.tick();assertTrue(f.tasks.isEmpty());
    assertEquals(1,f.pipeline.queueMetrics().pending());assertEquals(1,f.pipeline.queueMetrics().replaced());
    f.time.advance(10);f.pipeline.pollForeground();f.run();
    verify(f.store,times(3)).append(any());
    var windows=org.mockito.ArgumentCaptor.forClass(ForegroundWindow.class);verify(f.extractor,times(2)).extract(any(),windows.capture());
    assertEquals(f.foreground,windows.getValue());assertEquals(2,f.pipeline.queueMetrics().started());
    assertTrue(f.pipeline.queueMetrics().deferred()>0);
  }
  @Test void inFlightWorkNeverRunsInParallelAndLateResultKeepsOriginalId() throws Exception {
    var f=new Fixture();
    when(f.extractor.extract(any(),any())).thenAnswer(i->{
      if(f.pipeline.queueMetrics().started()==1){f.time.advance(15);f.change("B");f.pipeline.tick();assertTrue(f.tasks.isEmpty());}
      return new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.9);
    });
    f.pipeline.tick();f.run();verify(f.extractor).extract(any(),any());
    var originals=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(f.store,times(2)).append(originals.capture());
    verify(f.store).replace(argThat(r->r.id().equals(originals.getAllValues().getFirst().id()) && r.detection().visionDiagnostics().foreground().state()==VisionDiagnostics.State.USED));
    assertEquals(1,f.pipeline.queueMetrics().pending());
    f.time.advance(15);f.change("C");f.pipeline.tick();
    var later=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(f.store,times(3)).append(later.capture());
    assertEquals(originals.getAllValues().getLast().foreground(),later.getValue().detection().evidence().history().foreground());
    f.run();verify(f.extractor,times(2)).extract(any(),any());
  }
  @Test void failureAllowsAnotherAttemptAndTimingsReferenceTheSource() throws Exception {
    var f=new Fixture();when(f.extractor.extract(any(),any())).thenThrow(new java.io.IOException()).thenAnswer(i->{f.time.advance(12);return new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.9);});
    f.pipeline.tick();f.run();f.time.advance(30);f.pipeline.tick();f.time.advance(3);f.run();
    var updated=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(f.store,atLeastOnce()).replace(updated.capture());
    var record=updated.getValue();var timing=record.detection().visionDiagnostics().foreground().timing();
    assertEquals(record.id(),timing.observationId());assertEquals(record.capturedAt(),timing.imageCapturedAt());
    assertEquals(12,Duration.between(timing.startedAt(),timing.completedAt()).getSeconds());
    assertEquals(1,f.pipeline.queueMetrics().failed());assertEquals(1,f.pipeline.queueMetrics().completed());
    assertEquals(3000,f.pipeline.queueMetrics().queueWaitMillis());
    assertEquals(12000,f.pipeline.queueMetrics().executionMillis());assertEquals(15000,f.pipeline.queueMetrics().endToEndMillis());
  }
  @Test void disablingOptimizationRestoresRepeatedAnalysis() throws Exception {
    var f=new Fixture();f.p.getVisionQueue().setEnabled(false);
    f.pipeline.tick();f.run();f.pipeline.tick();f.run();verify(f.extractor,times(2)).extract(any(),any());
  }
  @Test void fiveMinuteSyntheticComparisonKeepsCoverageAndReducesDuplicateCalls() throws Exception {
    for(boolean enabled:List.of(false,true)) {
      var f=new Fixture();f.p.getVisionQueue().setEnabled(enabled);
      for(int i=0;i<=20;i++){f.pipeline.tick();while(!f.tasks.isEmpty())f.run();f.time.advance(15);}
      verify(f.store,times(21)).append(any());verify(f.observer,times(enabled?2:21)).capture();
      assertEquals(enabled?2:21,f.pipeline.queueMetrics().apiCalls());
      assertEquals(enabled?19:0,f.pipeline.queueMetrics().duplicateSkipped());
      System.out.printf("Activity vision synthetic comparison: optimized=%s observations=21 captures=%d apiCalls=%d duplicates=%d%n",enabled,enabled?2:21,f.pipeline.queueMetrics().apiCalls(),f.pipeline.queueMetrics().duplicateSkipped());
    }
  }
  @Test void imageTimestampIsCaptureCompletionAndOldJsonRemainsReadable() throws Exception {
    var f=new Fixture();var foregroundReads=new java.util.concurrent.atomic.AtomicInteger();
    when(f.observer.foreground()).thenAnswer(i->{if(foregroundReads.incrementAndGet()==3)f.time.advance(5);return f.foreground;});
    when(f.observer.capture()).thenAnswer(i->{f.time.advance(1);return ActivityChangeScopeTest.screen(20,20);});
    f.pipeline.tick();f.run();
    var records=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(f.store,atLeastOnce()).replace(records.capture());
    var record=records.getValue();var timing=record.detection().visionDiagnostics().foreground().timing();
    assertEquals(record.capturedAt().plusSeconds(1),timing.imageCapturedAt());
    assertEquals(5000,f.pipeline.queueMetrics().queueWaitMillis());
    var mapper=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    assertEquals(record,mapper.readValue(mapper.writeValueAsString(record),ActivityRecord.class));
    var old=mapper.readValue("{\"state\":\"ATTEMPTED_FAILED\",\"failure\":\"TIMEOUT\"}",VisionDiagnostics.Result.class);
    assertNull(old.timing());assertEquals(VisionDiagnostics.State.ATTEMPTED_FAILED,old.state());
  }
  @Test void intervalIsMeasuredBetweenStartsRatherThanCompletions() throws Exception {
    var f=new Fixture();when(f.extractor.extract(any(),any())).thenAnswer(i->{if(f.pipeline.queueMetrics().started()==1)f.time.advance(25);return new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.9);});
    f.pipeline.tick();f.run();f.time.advance(5);f.change("B");f.pipeline.tick();f.run();
    var updated=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(f.store,atLeastOnce()).replace(updated.capture());
    var starts=updated.getAllValues().stream().map(r->r.detection().visionDiagnostics().foreground()).filter(r->r.state()==VisionDiagnostics.State.USED).map(r->r.timing().startedAt()).toList();
    assertEquals(2,starts.size());assertEquals(30,Duration.between(starts.getFirst(),starts.getLast()).getSeconds());
  }
  @Test void contextCaptureTimeDoesNotCauseDuplicatesButRevisionAndGitChangesDo() throws Exception {
    var revision=new java.util.concurrent.atomic.AtomicLong(1);var branch=new java.util.concurrent.atomic.AtomicReference<>("main");
    ActivityEvidenceSource source=at->new ActivityEvidenceSource.Contribution("rei","p",List.of(),new ActivityEvidence.WorkReference("p",at,revision.get(),at,new ActivityEvidence.GitReference(branch.get(),"commit",at),List.of(),false));
    var f=new Fixture(List.of(source));f.p.getDetection().setEvidenceEnabled(false);
    f.pipeline.tick();f.run();f.time.advance(15);f.pipeline.tick();verify(f.observer).capture();
    revision.set(2);f.time.advance(15);f.pipeline.tick();f.run();
    branch.set("feature");f.time.advance(30);f.pipeline.tick();f.run();verify(f.observer,times(3)).capture();
  }
  @Test void duplicateKeepsClassificationAsBoundedHistoricalInferenceWithSourceId() throws Exception {
    var f=new Fixture();f.pipeline.tick();f.run();f.time.advance(15);f.pipeline.tick();
    var records=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(f.store,times(2)).append(records.capture());
    var current=records.getValue();
    assertEquals("social",current.inference().activities().getFirst().type());assertTrue(current.confidence()<=.7);
    assertTrue(current.detection().classificationSources().contains("HISTORY"));assertFalse(current.detection().visionUsed());
    assertEquals(VisionDiagnostics.State.NOT_ATTEMPTED,current.detection().visionDiagnostics().foreground().state());
    assertEquals(records.getAllValues().getFirst().id(),current.detection().visionDiagnostics().foreground().timing().observationId());
  }
}
