package dev.mikoto2000.rei.activity;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityParallelTest {
  @Test void blockedBackgroundStillAllowsOsRecordingAndQueuesLatestForeground() throws Exception {
    var f=new DesktopContextQueueTest.Fixture();var active=new java.util.concurrent.atomic.AtomicInteger();
    when(f.extractor.extract(any(),any())).thenAnswer(i->{
      assertEquals(1,active.incrementAndGet());
      try {
        if("background".equals(org.slf4j.MDC.get("activityScope"))){f.time.advance(30);f.change("new");f.pipeline.tick();assertEquals(1,f.pipeline.queueMetrics().pending());verify(f.store,times(2)).append(any());throw new java.io.IOException();}
        return new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.9);
      }finally{active.decrementAndGet();}
    });
    f.pipeline.tick();f.run();assertEquals(2,f.pipeline.queueMetrics().completed());
  }
  @Test void backgroundHasOneLatestSlotAndLateResultKeepsOriginalObservation() throws Exception {
    var f=new DesktopContextQueueTest.Fixture();f.pipeline.tick();f.time.advance(300);f.change("B");f.pipeline.tick();
    assertEquals(1,f.tasks.size());assertEquals(1,f.pipeline.desktopPending());f.run();
    var records=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(f.store,times(2)).append(records.capture());
    verify(f.store).replace(argThat(r->r.id().equals(records.getAllValues().getLast().id()) && r.detection().visionDiagnostics().background().state()==VisionDiagnostics.State.USED));
  }
  @Test void pauseDiscardsQueuedBackgroundAndDoesNotPublishAfterResume() throws Exception {
    var f=new DesktopContextQueueTest.Fixture();f.pipeline.tick();f.pipeline.pause();f.pipeline.resume();f.run();verifyNoInteractions(f.extractor);
  }
  @Test void desktopNeverExecutesBeforeForeground() throws Exception {
    var f=new DesktopContextQueueTest.Fixture();var scopes=new ArrayList<String>();
    when(f.extractor.extract(any(),any())).thenAnswer(i->{scopes.add(org.slf4j.MDC.get("activityScope"));return new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.9);});
    f.pipeline.tick();f.run();assertEquals(List.of("foreground","background"),scopes);
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
  void pauseOrCloseDuringBackgroundCallDiscardsResult(boolean close) throws Exception {
    var f=new DesktopContextQueueTest.Fixture();
    when(f.extractor.extract(any(),any())).thenAnswer(i->{
      if("background".equals(org.slf4j.MDC.get("activityScope"))){if(close)f.pipeline.close();else {f.pipeline.pause();f.pipeline.resume();}}
      return new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.9);
    });
    f.pipeline.tick();f.run();verify(f.store,never()).replace(argThat(r->r.detection().visionDiagnostics().background().state()==VisionDiagnostics.State.USED));
  }
}
