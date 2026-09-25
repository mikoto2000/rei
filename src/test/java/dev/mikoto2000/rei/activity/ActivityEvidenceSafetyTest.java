package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityEvidenceSafetyTest {
  @Test void unknownForegroundBoundsDoNotSilentlySendFullDesktop() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var store=mock(ActivityStore.class);
    when(observer.foreground()).thenReturn(new ForegroundWindow("Firefox",1,"Mozilla Firefox","1"));when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(10,20));
    new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),new ActivityChangeScopeTest.Time()).tick();
    verify(store).append(any());verifyNoInteractions(extractor);
  }
  @Test void optInBackgroundHasOneWorkerAndEnrichesOriginalObservation() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);p.getDetection().setBackgroundFullScreenEnabled(true);p.setBackgroundAnalysisIntervalSeconds(60);
    var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var store=mock(ActivityStore.class);var time=new ActivityChangeScopeTest.Time();
    when(observer.foreground()).thenReturn(ActivityEvidenceClassifierTest.window("Firefox","ホーム / X"));when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(10,20));
    when(extractor.extract(any(),any())).thenReturn(new ActivityExtractor.Result(new ActivityRecord.Inference("YouTube visible",List.of(new ActivityRecord.Activity("primary","media","Chrome","YouTube","",""))),.9));
    var backgrounds=new ArrayList<Runnable>();var capture=new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),time,Runnable::run,backgrounds::add);
    capture.tick();time.advance(60);capture.tick();assertEquals(1,backgrounds.size());verify(store,times(2)).append(any());
    backgrounds.getFirst().run();var appended=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);var updated=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);
    verify(store,times(2)).append(appended.capture());verify(store,times(2)).replace(updated.capture());
    assertEquals(appended.getAllValues().getFirst().id(),updated.getValue().id());assertEquals("social",new ActivityRolePolicy().classify(updated.getValue()).primary().type());
  }
  @Test void disabledFallbackOrVisionStillStoresUnknownObservationWithoutPixels() throws Exception {
    for(boolean disableVision:List.of(false,true)) {
      var p=new ActivityProperties();p.setEnabled(true);
      if(disableVision)p.getDetection().setVisionEnabled(false);else p.getDetection().setFallbackEnabled(false);
      var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var store=mock(ActivityStore.class);
      when(observer.foreground()).thenReturn(ActivityEvidenceClassifierTest.window("Firefox","Mozilla Firefox"));
      new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),new ActivityChangeScopeTest.Time()).tick();
      verify(store).append(any());verifyNoInteractions(extractor);verify(observer,never()).capture();
    }
  }
  @Test void screenshotFailureDoesNotEraseOsObservation() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);var observer=mock(DesktopActivityObserver.class);var store=mock(ActivityStore.class);var extractor=mock(ActivityExtractor.class);
    when(observer.foreground()).thenReturn(ActivityEvidenceClassifierTest.window("Firefox","Mozilla Firefox"));
    when(observer.capture()).thenThrow(new java.io.IOException());
    new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),new ActivityChangeScopeTest.Time()).tick();
    verify(store).append(any());verifyNoInteractions(extractor);
  }
  @Test void backgroundMotionNeverTriggersDefaultVisionEvenAfterInterval() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var store=mock(ActivityStore.class);
    when(observer.foreground()).thenReturn(ActivityEvidenceClassifierTest.window("Firefox","ホーム / X"));
    var time=new ActivityChangeScopeTest.Time();var backgrounds=new ArrayList<Runnable>();
    var capture=new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),time,Runnable::run,backgrounds::add);
    for(int i=0;i<8;i++){capture.tick();time.advance(60);}
    verify(store,times(8)).append(any());verify(observer,never()).capture();verifyNoInteractions(extractor);assertTrue(backgrounds.isEmpty());
  }
  @Test void observationContinuesWhileVisionIsActuallyBlockedAndPauseRejectsLateResult() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var store=mock(ActivityStore.class);
    when(observer.foreground()).thenReturn(ActivityEvidenceClassifierTest.window("Firefox","Mozilla Firefox"));when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(10,20));
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    when(extractor.extract(any(),any())).thenAnswer(i->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.9);});
    var time=new ActivityChangeScopeTest.Time();
    try(var worker=Executors.newSingleThreadExecutor()) {
      var capture=new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),time,worker,Runnable::run);
      try {
        capture.tick();assertTrue(entered.await(5,TimeUnit.SECONDS));
        for(int i=0;i<3;i++){time.advance(60);capture.tick();}
        verify(store,times(4)).append(any());verify(store).replace(argThat(r->r.detection().visionDiagnostics().foreground().state()==VisionDiagnostics.State.ATTEMPTED));
        clearInvocations(store);capture.pause();release.countDown();
        worker.submit(()->{}).get(5,TimeUnit.SECONDS);
        verify(extractor).extract(any(),any());verify(store,never()).replace(any());
      } finally {release.countDown();capture.close();}
    }
  }
  @Test void optionalSourceFailureAndPrivateInvisibleWindowsAreIsolated() {
    var p=new ActivityProperties();var fg=ActivityEvidenceClassifierTest.window("Code","A.java - rei - Visual Studio Code");
    var privateWindow=new ForegroundWindow("Firefox",2,"Enter Password","2");
    var social=new ForegroundWindow("Firefox",3,"ホーム / X","3");
    var windows=List.of(new ActivityEvidence.VisibleWindow(privateWindow,true,false,false,"m1"),new ActivityEvidence.VisibleWindow(social,false,false,false,"m1"),new ActivityEvidence.VisibleWindow(social,true,true,false,"m1"),new ActivityEvidence.VisibleWindow(social,true,false,true,"m1"));
    var aggregator=new ActivityEvidenceAggregator(p,List.of(at->{throw new IllegalStateException("private");},at->new ActivityEvidenceSource.Contribution("rei","p",List.of())));
    var evidence=aggregator.collect(Instant.now(),new DesktopActivityObserver.Metadata(fg,windows),null);
    assertTrue(evidence.visibleWindows().isEmpty());assertEquals("rei",evidence.projectName());assertEquals("development",new ActivityClassifier().classify(evidence).inference().activities().getFirst().type());
  }
  @Test void foregroundExclusionBlocksMetadataPersistenceAndVision() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);var observer=mock(DesktopActivityObserver.class);var store=mock(ActivityStore.class);var extractor=mock(ActivityExtractor.class);
    when(observer.foreground()).thenReturn(new ForegroundWindow("Firefox",1,"Enter Password","1"));
    new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),new ActivityChangeScopeTest.Time()).tick();
    verifyNoInteractions(store,extractor);verify(observer,never()).capture();
  }
  @Test void projectAndRecentToolTogetherCanIdentifyTerminalButHistoryAloneCannot() {
    var at=Instant.now();var fg=new ForegroundWindow("WindowsTerminal",1,"rei","1");
    var evidence=new ActivityEvidence(at,fg,List.of(),"rei","p",List.of(new ActivityEvidence.RecentEvent(at,"p","SHELL")),null);
    assertEquals(.9,new ActivityClassifier().classify(evidence).confidence());
    var history=new ActivityEvidence.History(at,fg,new ActivityRecord.Inference("old",List.of()),.99);
    assertEquals(0,new ActivityClassifier().classify(new ActivityEvidence(at,fg,List.of(),"rei","p",List.of(),history)).confidence());
  }
  @Test void configBindsDefaultsAndRejectsInvalidThresholds() {
    var defaults=new ActivityProperties();assertEquals(ActivityProperties.DetectionMode.EVIDENCE_FIRST,defaults.getDetection().getMode());
    assertEquals(.8,defaults.getDetection().getSkipVisionConfidence());assertFalse(defaults.getDetection().isBackgroundFullScreenEnabled());assertEquals(2048,defaults.getDetection().getMaxOutputTokens());
    var binder=new org.springframework.boot.context.properties.bind.Binder(new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of("rei.activity.detection.mode","vision-first","rei.activity.detection.max-output-tokens","512")));
    var p=binder.bind("rei.activity",ActivityProperties.class).get();assertEquals(ActivityProperties.DetectionMode.VISION_FIRST,p.getDetection().getMode());assertEquals(512,p.getDetection().getMaxOutputTokens());
    for(double value:new double[]{-.1,1.1,Double.NaN}){p.getDetection().setSkipVisionConfidence(value);assertThrows(IllegalArgumentException.class,p::validate);}
  }
  @Test void skipThresholdIsInclusive() throws Exception {
    for(double threshold:new double[]{.95,.96}) {
      var p=new ActivityProperties();p.setEnabled(true);p.getDetection().setSkipVisionConfidence(threshold);
      var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);
      when(observer.foreground()).thenReturn(ActivityEvidenceClassifierTest.window("Firefox","ホーム / X"));when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(10,20));
      var tasks=new ArrayList<Runnable>();
      new ActivityCapture(p,observer,extractor,mock(ActivityStore.class),mock(ScreenshotStore.class),new ActivityChangeScopeTest.Time(),tasks::add,Runnable::run).tick();
      assertEquals(threshold==.95?0:1,tasks.size());
    }
  }
}
