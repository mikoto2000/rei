package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.computeruse.*;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityChangeScopeTest {
  static class Time extends Clock {
    Instant now=Instant.parse("2026-09-23T01:00:00Z");
    public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;}
    public Instant instant(){return now;} void advance(int seconds){now=now.plusSeconds(seconds);}
  }
  static CapturedScreen screen(int front,int background) {
    var image=new BufferedImage(32,32,BufferedImage.TYPE_INT_RGB);
    for(int y=0;y<32;y++) for(int x=0;x<32;x++){int c=x<16?front:background;image.setRGB(x,y,(c<<16)|(c<<8)|c);}
    return new CapturedScreen(image,new Rectangle(0,0,32,32));
  }
  @Test void desktopIsPeriodicRatherThanTriggeredByBackgroundMotion() throws Exception {
    var f=new DesktopContextQueueTest.Fixture();f.pipeline.tick();f.run();f.time.advance(60);f.pipeline.tick();
    assertTrue(f.tasks.isEmpty());verify(f.extractor,times(2)).extract(any(),any());
    f.time.advance(240);f.pipeline.tick();f.run();verify(f.extractor,times(4)).extract(any(),any());
  }
  @Test void foregroundMotionImmediatelyTriggersAnalysis() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);p.getDetection().setMode(ActivityProperties.DetectionMode.VISION_FIRST);p.getObservation().setInputAwareEnabled(false);var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);
    when(observer.foreground()).thenReturn(new ForegroundWindow("firefox",1,"X","1",new ActivityRecord.Bounds(0,0,16,32)));
    when(observer.capture()).thenReturn(screen(10,10),screen(200,10));
    when(extractor.extract(any(),any())).thenReturn(new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.8));
    var capture=new ActivityCapture(p,observer,extractor,mock(ActivityStore.class),mock(ScreenshotStore.class),new Time());
    capture.tick();capture.tick();verify(extractor,times(2)).extract(any(),any());
  }
  @Test void foregroundChangeAloneDoesNotCountAsBackgroundChangeWhenRefreshIsDue() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);p.getDetection().setMode(ActivityProperties.DetectionMode.VISION_FIRST);p.getObservation().setInputAwareEnabled(false);var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var time=new Time();
    when(observer.foreground()).thenReturn(new ForegroundWindow("firefox",1,"X","1",new ActivityRecord.Bounds(0,0,16,32)));
    when(observer.capture()).thenReturn(screen(10,10),screen(200,10));
    when(extractor.extract(any(),any())).thenReturn(new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.8));
    var capture=new ActivityCapture(p,observer,extractor,mock(ActivityStore.class),mock(ScreenshotStore.class),time);
    capture.tick();time.advance(300);capture.tick();
    var images=org.mockito.ArgumentCaptor.forClass(CapturedScreen.class);verify(extractor,times(2)).extract(images.capture(),any());
    assertEquals(16,images.getAllValues().getLast().image().getWidth());
  }
  @Test void desktopResultIsNotReusedAsFreshBackgroundEvidenceOnForegroundOnlyTicks() throws Exception {
    var f=new DesktopContextQueueTest.Fixture();f.pipeline.tick();f.run();f.time.advance(300);f.pipeline.tick();f.run();
    f.time.advance(60);f.pipeline.tick();assertTrue(f.tasks.isEmpty());
    var records=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(f.store,times(3)).append(records.capture());
    assertNull(records.getValue().detection().visionDiagnostics().background().context());
    assertEquals(VisionDiagnostics.State.NOT_ATTEMPTED,records.getValue().detection().visionDiagnostics().background().state());
  }
}
