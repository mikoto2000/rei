package dev.mikoto2000.rei.activity;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityParallelTest {
  @Test void blockedBackgroundDoesNotBlockForegroundAndItsFailureDoesNotResetReuse() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);p.setBackgroundAnalysisIntervalSeconds(60);
    var observer=mock(DesktopActivityObserver.class);var store=mock(ActivityStore.class);
    var time=new ActivityChangeScopeTest.Time();
    var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
    var finished=new java.util.concurrent.CountDownLatch(1);
    var frontCalls=new java.util.concurrent.atomic.AtomicInteger();var backCalls=new java.util.concurrent.atomic.AtomicInteger();
    when(observer.foreground()).thenReturn(new ForegroundWindow("firefox",1,"X","1",new ActivityRecord.Bounds(0,0,16,32)));
    when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(10,10),ActivityChangeScopeTest.screen(100,200),ActivityChangeScopeTest.screen(100,50));
    ActivityExtractor extractor=(screen,fg)->{
      if(screen.image().getWidth()==32) {backCalls.incrementAndGet();entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));throw new java.io.IOException("test failure");}
      frontCalls.incrementAndGet();return new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.8);
    };
    var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
    try(var capture=new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),time,Runnable::run,
        task->worker.execute(()->{try{task.run();}finally{finished.countDown();}}))) {
      capture.tick();time.advance(60);capture.tick();assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
      time.advance(60);capture.tick();verify(store,times(3)).append(any());assertEquals(2,frontCalls.get());assertEquals(1,backCalls.get());
      release.countDown();assertTrue(finished.await(5,java.util.concurrent.TimeUnit.SECONDS));
      time.advance(60);capture.tick();assertEquals(2,frontCalls.get());verify(store,times(4)).append(any());
    } finally {release.countDown();worker.shutdownNow();}
  }
  @Test void backgroundHasOneJobWithoutQueueWhileForegroundKeepsSaving() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);p.setBackgroundAnalysisIntervalSeconds(60);
    var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var store=mock(ActivityStore.class);
    var time=new ActivityChangeScopeTest.Time();var backgrounds=new ArrayList<Runnable>();
    when(observer.foreground()).thenReturn(new ForegroundWindow("firefox",1,"X","1",new ActivityRecord.Bounds(0,0,16,32)));
    when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(10,10),ActivityChangeScopeTest.screen(20,200),ActivityChangeScopeTest.screen(100,50));
    when(extractor.extract(any(),any())).thenReturn(new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.8));
    var capture=new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),time,Runnable::run,backgrounds::add);
    capture.tick();time.advance(60);capture.tick();time.advance(60);capture.tick();
    assertEquals(1,backgrounds.size());verify(store,times(3)).append(any());
    var images=org.mockito.ArgumentCaptor.forClass(dev.mikoto2000.rei.computeruse.CapturedScreen.class);
    verify(extractor,times(3)).extract(images.capture(),any());assertTrue(images.getAllValues().stream().allMatch(s->s.image().getWidth()==16));
    backgrounds.getFirst().run();
    var records=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(store,times(3)).append(records.capture());
    var updated=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(store).replace(updated.capture());
    assertEquals(records.getAllValues().get(1).id(),updated.getValue().id());
    assertEquals(records.getAllValues().get(1).capturedAt(),updated.getValue().capturedAt());
    assertNotEquals(records.getAllValues().get(2).id(),updated.getValue().id());
  }
  @Test void pauseDiscardsQueuedBackgroundAndDoesNotPublishItAfterResume() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);p.setBackgroundAnalysisIntervalSeconds(60);
    var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var store=mock(ActivityStore.class);
    var time=new ActivityChangeScopeTest.Time();var backgrounds=new ArrayList<Runnable>();
    when(observer.foreground()).thenReturn(new ForegroundWindow("firefox",1,"X","1",new ActivityRecord.Bounds(0,0,16,32)));
    when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(10,10),ActivityChangeScopeTest.screen(20,200));
    when(extractor.extract(any(),any())).thenReturn(new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.8));
    var capture=new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),time,Runnable::run,backgrounds::add);
    capture.tick();time.advance(60);capture.tick();capture.pause();capture.resume();backgrounds.forEach(Runnable::run);
    verify(extractor,times(2)).extract(any(),any());verify(store,never()).replace(any());
  }
  @Test void backgroundCompletingFirstIsSupplementedWhenForegroundEventuallySaves() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);p.setBackgroundAnalysisIntervalSeconds(60);
    var observer=mock(DesktopActivityObserver.class);var store=mock(ActivityStore.class);var time=new ActivityChangeScopeTest.Time();
    var tasks=new ArrayList<Runnable>();
    var social=new ActivityRecord.Activity("primary","social","Firefox","X","","");
    var media=new ActivityRecord.Activity("primary","media","Chrome","YouTube","","");
    ActivityExtractor extractor=(screen,fg)->new ActivityExtractor.Result(new ActivityRecord.Inference("visible",List.of(screen.image().getWidth()==16?social:media)),.9);
    when(observer.foreground()).thenReturn(new ForegroundWindow("firefox",1,"X","1",new ActivityRecord.Bounds(0,0,16,32)));
    when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(10,10),ActivityChangeScopeTest.screen(100,200));
    var capture=new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),time,tasks::add,Runnable::run);
    capture.tick();time.advance(60);capture.tick();verifyNoInteractions(store);
    tasks.getFirst().run();
    var records=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(store).append(records.capture());
    assertEquals(List.of(social,media),records.getValue().inference().activities());
    assertEquals(60,records.getValue().durationEstimate());verify(store,never()).replace(any());
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
  void pauseOrCloseDuringBackgroundCallDiscardsCompletedResult(boolean close) throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);p.setBackgroundAnalysisIntervalSeconds(60);
    var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var store=mock(ActivityStore.class);
    var time=new ActivityChangeScopeTest.Time();
    when(observer.foreground()).thenReturn(new ForegroundWindow("firefox",1,"X","1",new ActivityRecord.Bounds(0,0,16,32)));
    when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(10,10),ActivityChangeScopeTest.screen(100,200));
    var capture=new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),time,Runnable::run,Runnable::run);
    when(extractor.extract(any(),any())).thenAnswer(i->{
      if(((dev.mikoto2000.rei.computeruse.CapturedScreen)i.getArgument(0)).image().getWidth()==32) {
        if(close) capture.close();else {capture.pause();capture.resume();}
      }
      return new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.8);
    });
    capture.tick();time.advance(60);capture.tick();verify(store,times(2)).append(any());verify(store,never()).replace(any());
  }
}
