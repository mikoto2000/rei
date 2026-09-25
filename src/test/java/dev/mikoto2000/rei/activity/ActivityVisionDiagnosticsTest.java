package dev.mikoto2000.rei.activity;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityVisionDiagnosticsTest {
  ActivityRecord run(boolean background,ActivityExtractor.Result result,Exception failure) throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);p.getDetection().setBackgroundFullScreenEnabled(background);
    var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var store=mock(ActivityStore.class);
    when(observer.foreground()).thenReturn(new ForegroundWindow("Firefox",1,background?"ホーム / X":"Mozilla Firefox","1",new ActivityRecord.Bounds(0,0,16,16)));
    when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(20,20));
    if(failure!=null)when(extractor.extract(any(),any())).thenThrow(failure);else when(extractor.extract(any(),any())).thenReturn(result);
    new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),new ActivityChangeScopeTest.Time()).tick();
    var records=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(store,atLeastOnce()).replace(records.capture());
    var attempt=records.getAllValues().getFirst().detection().visionDiagnostics();
    assertEquals(VisionDiagnostics.State.ATTEMPTED,(background?attempt.background():attempt.foreground()).state());
    return records.getValue();
  }
  @Test void failedForegroundKeepsWindowAndStoresFailureTaxonomy() throws Exception {
    var r=run(false,null,new java.net.SocketTimeoutException("private response"));
    assertEquals("Window",new ActivityEvidenceDisplayFormatter().evidence(r));
    assertEquals(new VisionDiagnostics.Result(VisionDiagnostics.State.ATTEMPTED_FAILED,ActivityVisionFailure.TIMEOUT),r.detection().visionDiagnostics().foreground());
    assertFalse(new ActivityEvidenceDisplayFormatter().verbose(r).contains("private response"));
  }
  @Test void foregroundUsedAndSuccessWithoutContributionAreDifferent() throws Exception {
    var used=run(false,new ActivityExtractor.Result(new ActivityRecord.Inference("private",List.of(ActivitySemanticTest.activity("social","Firefox","X",""))),.9),null);
    assertEquals("Window + Foreground Vision",new ActivityEvidenceDisplayFormatter().evidence(used));
    var unused=run(false,new ActivityExtractor.Result(new ActivityRecord.Inference("private",List.of(ActivitySemanticTest.activity("unknown","Firefox","",""))),0),null);
    assertEquals(VisionDiagnostics.State.ATTEMPTED_SUCCEEDED_NOT_USED,unused.detection().visionDiagnostics().foreground().state());
    assertEquals("Window",new ActivityEvidenceDisplayFormatter().evidence(unused));
  }
  @Test void backgroundRequiresAcceptedCandidate() throws Exception {
    var inference=new ActivityRecord.Inference("private",List.of(ActivitySemanticTest.activity("media","Chrome","YouTube","")));
    var used=run(true,new ActivityExtractor.Result(inference,.9),null);var unused=run(true,new ActivityExtractor.Result(inference,.1),null);
    assertEquals("Window + Background Vision",new ActivityEvidenceDisplayFormatter().evidence(used));
    assertEquals(VisionDiagnostics.State.ATTEMPTED_SUCCEEDED_NOT_USED,unused.detection().visionDiagnostics().background().state());
    assertEquals("Window",new ActivityEvidenceDisplayFormatter().evidence(unused));
  }
}
