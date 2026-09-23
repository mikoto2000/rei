package dev.mikoto2000.rei.activity;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityEvidencePipelineTest {
  @Test void codeAndXSaveWithoutScreenshotOrVision() throws Exception {
    for(var fg:List.of(ActivityEvidenceClassifierTest.window("Code.exe","BehaviorEvaluator.java - rei - Visual Studio Code"),ActivityEvidenceClassifierTest.window("Firefox","ホーム / X"))) {
      var p=new ActivityProperties();p.setEnabled(true);var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var store=mock(ActivityStore.class);
      when(observer.foreground()).thenReturn(fg);
      var capture=new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),new ActivityChangeScopeTest.Time());capture.tick();
      var records=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(store).append(records.capture());
      assertFalse(records.getValue().detection().visionUsed());assertEquals("EVIDENCE_ONLY",records.getValue().detection().classificationMode());
      verify(observer,never()).capture();verifyNoInteractions(extractor);
    }
  }
  @Test void pendingReplacementAndFailureNeverLoseEvidence() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var store=mock(ActivityStore.class);
    when(observer.foreground()).thenReturn(ActivityEvidenceClassifierTest.window("Firefox","Mozilla Firefox"));
    when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(20,20));
    when(extractor.extract(any(),any())).thenThrow(new java.net.SocketTimeoutException("private payload"));
    var tasks=new ArrayList<Runnable>();var background=new ArrayList<Runnable>();var time=new ActivityChangeScopeTest.Time();
    var capture=new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),time,tasks::add,background::add);
    for(int i=0;i<4;i++) {capture.tick();time.advance(60);}
    verify(store,times(4)).append(any());verifyNoInteractions(extractor);assertEquals(1,tasks.size());assertTrue(background.isEmpty());
    tasks.getFirst().run();verify(extractor).extract(any(),any());verify(store,times(4)).append(any());
  }
  @Test void fallbackPersistsFirstAndEnrichesTheSameIdWithCrop() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var store=mock(ActivityStore.class);
    when(observer.foreground()).thenReturn(new ForegroundWindow("Firefox",1,"Mozilla Firefox","1",new ActivityRecord.Bounds(0,0,16,32)));
    when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(20,200));
    when(extractor.extract(any(),any())).thenAnswer(i->{verify(store).append(any());assertEquals(16,((dev.mikoto2000.rei.computeruse.CapturedScreen)i.getArgument(0)).image().getWidth());return new ActivityExtractor.Result(new ActivityRecord.Inference("visible",List.of(new ActivityRecord.Activity("primary","social","Firefox","X","",""))),.9);});
    var capture=new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),new ActivityChangeScopeTest.Time());capture.tick();
    var original=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);var updated=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);
    verify(store).append(original.capture());verify(store,atLeastOnce()).replace(updated.capture());
    assertEquals(original.getValue().id(),updated.getValue().id());assertEquals(60,updated.getValue().durationEstimate());
    assertTrue(updated.getValue().detection().visionUsed());assertEquals("social",updated.getValue().inference().activities().getFirst().type());
  }
}
