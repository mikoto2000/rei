package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityPipelineTest {
  @Test void observationContinuesWhileVisionRunsAndPendingImagesAreLatestOnly() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);p.getDetection().setMode(ActivityProperties.DetectionMode.VISION_FIRST);p.getDetection().setBackgroundFullScreenEnabled(true);
    var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var store=mock(ActivityStore.class);
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var written=new CountDownLatch(2);
    var time=new ActivityChangeScopeTest.Time();
    when(observer.foreground()).thenReturn(new ForegroundWindow("firefox",1,"X","1"));
    when(observer.capture()).thenReturn(ActivityCaptureTest.screen(10),ActivityCaptureTest.screen(100),ActivityCaptureTest.screen(200));
    when(extractor.extract(any(),any())).thenAnswer(i -> {entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.8);});
    doAnswer(i -> {written.countDown();return null;}).when(store).append(any());
    var executor=Executors.newSingleThreadExecutor();
    try(var capture=new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),time,executor)) {
      capture.tick();assertTrue(entered.await(5,TimeUnit.SECONDS));
      time.advance(60);capture.tick();time.advance(60);capture.tick();
      verify(observer,times(3)).capture();verify(extractor,times(1)).extract(any(),any());
      release.countDown();assertTrue(written.await(5,TimeUnit.SECONDS));
      var images=org.mockito.ArgumentCaptor.forClass(dev.mikoto2000.rei.computeruse.CapturedScreen.class);
      verify(extractor,times(2)).extract(images.capture(),any());
      assertEquals(200,images.getAllValues().getLast().image().getRGB(0,0)&255);
      var records=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(store,times(2)).append(records.capture());
      assertEquals(120,Duration.between(records.getAllValues().getFirst().capturedAt(),records.getAllValues().getLast().capturedAt()).toSeconds());
      assertTrue(records.getAllValues().stream().allMatch(r->r.durationEstimate()==60));
    } finally {release.countDown();executor.shutdownNow();}
  }
  @Test void pauseClearsPendingFramesAndInvalidatesInflightResult() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);p.getDetection().setMode(ActivityProperties.DetectionMode.VISION_FIRST);p.getDetection().setBackgroundFullScreenEnabled(true);var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var store=mock(ActivityStore.class);
    when(observer.foreground()).thenReturn(new ForegroundWindow("firefox",1,"X","1"));when(observer.capture()).thenReturn(ActivityCaptureTest.screen(20));
    var tasks=new ArrayList<Runnable>();
    var capture=new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),Clock.systemUTC(),tasks::add);
    capture.tick();capture.tick();capture.pause();tasks.forEach(Runnable::run);
    verifyNoInteractions(extractor,store);
  }
  @Test void excludedForegroundInvalidatesQueuedPublicImage() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);p.getDetection().setMode(ActivityProperties.DetectionMode.VISION_FIRST);p.getDetection().setBackgroundFullScreenEnabled(true);var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var store=mock(ActivityStore.class);
    when(observer.foreground()).thenReturn(new ForegroundWindow("firefox",1,"X","1"));when(observer.capture()).thenReturn(ActivityCaptureTest.screen(20));
    var tasks=new ArrayList<Runnable>();var capture=new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),Clock.systemUTC(),tasks::add);
    capture.tick();when(observer.foreground()).thenReturn(new ForegroundWindow("1password",2,"vault","2"));capture.tick();tasks.forEach(Runnable::run);
    verifyNoInteractions(extractor,store);
  }
  @Test void rejectedAnalysisSubmissionCanBeRetriedOnNextObservation() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);p.getDetection().setMode(ActivityProperties.DetectionMode.VISION_FIRST);p.getDetection().setBackgroundFullScreenEnabled(true);var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var store=mock(ActivityStore.class);
    when(observer.foreground()).thenReturn(new ForegroundWindow("firefox",1,"X","1"));when(observer.capture()).thenReturn(ActivityCaptureTest.screen(20));
    when(extractor.extract(any(),any())).thenReturn(new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.8));
    var attempts=new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.Executor executor=task->{if(attempts.getAndIncrement()==0)throw new RejectedExecutionException();task.run();};
    var capture=new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),Clock.systemUTC(),executor);
    capture.tick();capture.tick();verify(store).append(any());
  }
}
