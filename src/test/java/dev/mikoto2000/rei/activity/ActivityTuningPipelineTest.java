package dev.mikoto2000.rei.activity;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityTuningPipelineTest {
  @Test void conflictingOrUnknownVisionDoesNotEraseStrongerUsableEvidence() throws Exception {
    for(var result:List.of(new ActivityExtractor.Result(new ActivityRecord.Inference("unknown",List.of(new ActivityRecord.Activity("m","unknown","Firefox","","",""))),.99),
        new ActivityExtractor.Result(new ActivityRecord.Inference("different service",List.of(new ActivityRecord.Activity("m","shopping","Firefox","Amazon","",""))),.9))) {
      var p=new ActivityProperties();p.setEnabled(true);p.getDetection().setSkipVisionConfidence(1);
      var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var store=mock(ActivityStore.class);
      when(observer.foreground()).thenReturn(ActivityEvidenceClassifierTest.window("Firefox","X"));when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(10,20));when(extractor.extract(any(),any())).thenReturn(result);
      new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),new ActivityChangeScopeTest.Time()).tick();
      var saved=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(store).replace(saved.capture());
      assertEquals("social",saved.getValue().inference().activities().getFirst().type());assertEquals("X",saved.getValue().inference().activities().getFirst().service());assertEquals(.95,saved.getValue().confidence());
    }
  }
  @Test void knownCategoryWithMissingProjectAndContentSkipsVisionAndPersistsAxes() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var store=mock(ActivityStore.class);
    when(observer.foreground()).thenReturn(ActivityEvidenceClassifierTest.window("Firefox","YouTube"));
    new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),new ActivityChangeScopeTest.Time()).tick();
    var saved=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(store).append(saved.capture());
    assertTrue(saved.getValue().detection().fieldConfidence().usable(.8));assertFalse(saved.getValue().detection().fieldConfidence().complete());
    assertEquals(0,saved.getValue().detection().fieldConfidence().content());verifyNoInteractions(extractor);verify(observer,never()).capture();
  }
  @Test void visionEnrichesCategoryWithoutErasingKnownServiceOrAddingObservation() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var store=mock(ActivityStore.class);
    when(observer.foreground()).thenReturn(ActivityEvidenceClassifierTest.window("Firefox","ChatGPT"));when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(10,20));
    when(extractor.extract(any(),any())).thenReturn(new ActivityExtractor.Result(new ActivityRecord.Inference("調査",List.of(new ActivityRecord.Activity("primary","research","","","",""))),.9));
    new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),new ActivityChangeScopeTest.Time()).tick();
    var initial=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);var updated=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);
    verify(store).append(initial.capture());verify(store).replace(updated.capture());
    assertEquals(initial.getValue().id(),updated.getValue().id());assertEquals(60,updated.getValue().durationEstimate());
    var a=updated.getValue().inference().activities().getFirst();assertEquals("research",a.type());assertEquals("ChatGPT",a.service());assertEquals("Firefox",a.application());
    assertEquals(.9,updated.getValue().detection().fieldConfidence().category());assertEquals(.95,updated.getValue().detection().fieldConfidence().service());
  }
  @Test void outputLimitTimeoutAndValidationKeepPartialEvidence() throws Exception {
    for(Exception error:List.of(new ActivityOutputParser.InvalidOutput(List.of(new ActivityOutputParser.ResultError("/","output_limit"))),new java.net.SocketTimeoutException(),new ActivityOutputParser.InvalidOutput(List.of(new ActivityOutputParser.ResultError("/","invalid_json"))))) {
      var p=new ActivityProperties();p.setEnabled(true);var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var store=mock(ActivityStore.class);
      when(observer.foreground()).thenReturn(ActivityEvidenceClassifierTest.window("Firefox","ChatGPT"));when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(10,20));when(extractor.extract(any(),any())).thenThrow(error);
      new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),new ActivityChangeScopeTest.Time()).tick();
      verify(store).append(any());verify(extractor).extract(any(),any());var saved=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(store).replace(saved.capture());
      assertEquals("ChatGPT",saved.getValue().inference().activities().getFirst().service());assertEquals("unknown",saved.getValue().inference().activities().getFirst().type());
      assertTrue(saved.getValue().detection().fieldConfidence().partial());assertEquals("VISION_FAILED",saved.getValue().detection().status());
    }
  }
  @Test void failureTaxonomyDistinguishesLimitTimeoutAndValidation() {
    assertEquals(ActivityVisionFailure.OUTPUT_LIMIT,ActivityVisionFailure.classify(new ActivityOutputParser.InvalidOutput(List.of(new ActivityOutputParser.ResultError("/","output_limit")))));
    assertEquals(ActivityVisionFailure.TIMEOUT,ActivityVisionFailure.classify(new RuntimeException(new java.net.SocketTimeoutException())));
    assertEquals(ActivityVisionFailure.VALIDATION,ActivityVisionFailure.classify(new ActivityOutputParser.InvalidOutput(List.of(new ActivityOutputParser.ResultError("/","invalid_json")))));
  }
}
