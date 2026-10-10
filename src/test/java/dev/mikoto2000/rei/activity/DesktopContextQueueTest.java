package dev.mikoto2000.rei.activity;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DesktopContextQueueTest {
  static class Fixture extends ActivityVisionQueueTest.Fixture {
    Fixture() throws Exception {
      p.getDetection().setBackgroundFullScreenEnabled(true);
      foreground=new ForegroundWindow("Firefox",1,"Mozilla Firefox","1",new ActivityRecord.Bounds(0,0,16,32));
      when(observer.metadata()).thenAnswer(i->new DesktopActivityObserver.Metadata(foreground,List.of(),true));
    }
    @Override void change(String title){foreground=new ForegroundWindow("Firefox",1,"Mozilla Firefox "+title,"1",new ActivityRecord.Bounds(0,0,16,32));}
  }
  @Test void foregroundAndBackgroundShareOneWorkerAndForegroundWins() throws Exception {
    var f=new Fixture();f.pipeline.tick();
    assertEquals(1,f.tasks.size());
    assertEquals(1,f.pipeline.desktopPending());
    var scopes=new ArrayList<String>();
    when(f.extractor.extract(any(),any())).thenAnswer(i->{
      scopes.add(org.slf4j.MDC.get("activityScope"));
      if(scopes.size()==1){f.time.advance(30);f.change("B");f.pipeline.tick();assertEquals(0,f.tasks.size());}
      return new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.9);
    });
    f.run();assertEquals(List.of("foreground","foreground","background"),scopes);
    assertEquals(0,f.pipeline.desktopPending());
  }
  @Test void idleAndMissingEnumerationSuppressDesktopButKeepForeground() throws Exception {
    var f=new Fixture();when(f.observer.lightweight()).thenReturn(new DesktopActivityObserver.Lightweight(0,90000,"1","Default",false,true));
    f.pipeline.tick();f.run();verify(f.extractor).extract(any(),any());
    f.time.advance(300);f.change("new");when(f.observer.metadata()).thenAnswer(i->new DesktopActivityObserver.Metadata(f.foreground,List.of()));
    f.pipeline.tick();assertTrue(f.tasks.isEmpty());verify(f.extractor).extract(any(),any());verify(f.store,times(2)).append(any());
  }
  @Test void staleDesktopIsDiscardedAndShutdownDropsBothSlots() throws Exception {
    var f=new Fixture();f.pipeline.tick();f.time.advance(301);f.run();
    verify(f.extractor).extract(any(),any());
    f.time.advance(300);f.change("B");f.pipeline.tick();f.pipeline.close();f.run();
    verify(f.extractor).extract(any(),any());assertEquals(0,f.pipeline.desktopPending());
  }
  @Test void failedDesktopLeavesMainRecordAndWorkerAvailable() throws Exception {
    var f=new Fixture();when(f.extractor.extract(any(),any())).thenAnswer(i->{if("background".equals(org.slf4j.MDC.get("activityScope")))throw new java.io.IOException();return new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.9);});
    f.pipeline.tick();f.run();verify(f.store).append(any());
    f.time.advance(300);f.change("B");f.pipeline.tick();f.run();verify(f.store,times(2)).append(any());
    verify(f.extractor,times(4)).extract(any(),any());
  }
  @Test void monitorEvidenceAndInputAreSavedWithTheOriginalObservation() throws Exception {
    var f=new Fixture();f.pipeline.tick();f.run();
    var rows=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(f.store,atLeastOnce()).replace(rows.capture());
    var row=rows.getValue();assertEquals(List.of("primary"),row.observations().stream().map(ActivityRecord.Observation::monitor).toList());
    assertEquals(row.capturedAt(),row.observations().getFirst().capturedAt());
    assertNotNull(row.detection().evidence().input());
  }
}
