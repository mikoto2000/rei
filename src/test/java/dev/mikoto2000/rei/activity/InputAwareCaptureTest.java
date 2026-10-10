package dev.mikoto2000.rei.activity;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InputAwareCaptureTest {
  @Test void stableFiveMinutesUseTwoDetailedObservationsAndNeverFillTheGap() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);
    var observer=mock(DesktopActivityObserver.class);var store=mock(ActivityStore.class);
    var clock=new ActivityChangeScopeTest.Time();
    var ticks=new java.util.concurrent.atomic.AtomicLong(1000);
    when(observer.lightweight()).thenAnswer(i->new DesktopActivityObserver.Lightweight(100,ticks.get(),"1","Default",false,true));
    when(observer.foreground()).thenReturn(ActivityEvidenceClassifierTest.window("Code.exe","Test.java - rei - Visual Studio Code"));
    var capture=new ActivityCapture(p,observer,mock(ActivityExtractor.class),store,mock(ScreenshotStore.class),clock);
    for(int i=0;i<=20;i++){capture.tick();clock.advance(15);ticks.addAndGet(15000);}
    verify(observer,times(2)).metadata();verify(observer,never()).capture();
    var records=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(store,times(2)).append(records.capture());
    assertEquals(30,records.getAllValues().stream().mapToLong(ActivityRecord::durationEstimate).sum());
    var sessions=new SessionMergePolicy(java.time.Duration.ofSeconds(300),java.time.ZoneOffset.UTC).aggregate(records.getAllValues());
    assertEquals(30,sessions.stream().mapToLong(ActivitySession::observedSeconds).sum());
  }
  @Test void failedSaveRetriesAndDisabledOptimizationRestoresDuration() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);var observer=mock(DesktopActivityObserver.class);var store=mock(ActivityStore.class);
    when(observer.lightweight()).thenReturn(new DesktopActivityObserver.Lightweight(100,1000,"1","Default",false,true));
    when(observer.foreground()).thenReturn(ActivityEvidenceClassifierTest.window("Code.exe","Test.java - rei - Visual Studio Code"));
    doThrow(new IllegalStateException()).doNothing().when(store).append(any());
    var capture=new ActivityCapture(p,observer,mock(ActivityExtractor.class),store,mock(ScreenshotStore.class),new ActivityChangeScopeTest.Time());
    capture.tick();capture.tick();capture.tick();verify(store,times(2)).append(any());
    p.getObservation().setInputAwareEnabled(false);capture.tick();
    var records=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(store,times(3)).append(records.capture());
    assertEquals(60,records.getValue().durationEstimate());
  }
}
