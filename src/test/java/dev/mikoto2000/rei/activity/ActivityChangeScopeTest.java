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
  @Test void backgroundMotionDoesNotTriggerForegroundAnalysisButIsSampledPeriodically() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);var time=new Time();
    var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var store=mock(ActivityStore.class);
    when(observer.foreground()).thenReturn(new ForegroundWindow("firefox",1,"X","1",new ActivityRecord.Bounds(0,0,16,32)));
    when(observer.capture()).thenReturn(screen(10,10),screen(10,200),screen(10,200));
    when(extractor.extract(any(),any())).thenReturn(new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.8));
    var capture=new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),time);
    capture.tick();time.advance(60);capture.tick();
    verify(extractor,times(1)).extract(any(),any());verify(store,times(2)).append(any());
    time.advance(240);capture.tick();
    var images=org.mockito.ArgumentCaptor.forClass(CapturedScreen.class);verify(extractor,times(3)).extract(images.capture(),any());
    assertEquals(16,images.getAllValues().get(0).image().getWidth());
    assertEquals(16,images.getAllValues().get(1).image().getWidth());
    assertEquals(32,images.getAllValues().get(2).image().getWidth());
  }
  @Test void foregroundMotionImmediatelyTriggersAnalysis() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);
    when(observer.foreground()).thenReturn(new ForegroundWindow("firefox",1,"X","1",new ActivityRecord.Bounds(0,0,16,32)));
    when(observer.capture()).thenReturn(screen(10,10),screen(200,10));
    when(extractor.extract(any(),any())).thenReturn(new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.8));
    var capture=new ActivityCapture(p,observer,extractor,mock(ActivityStore.class),mock(ScreenshotStore.class),new Time());
    capture.tick();capture.tick();verify(extractor,times(2)).extract(any(),any());
  }
  @Test void foregroundChangeAloneDoesNotCountAsBackgroundChangeWhenRefreshIsDue() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var time=new Time();
    when(observer.foreground()).thenReturn(new ForegroundWindow("firefox",1,"X","1",new ActivityRecord.Bounds(0,0,16,32)));
    when(observer.capture()).thenReturn(screen(10,10),screen(200,10));
    when(extractor.extract(any(),any())).thenReturn(new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.8));
    var capture=new ActivityCapture(p,observer,extractor,mock(ActivityStore.class),mock(ScreenshotStore.class),time);
    capture.tick();time.advance(300);capture.tick();
    var images=org.mockito.ArgumentCaptor.forClass(CapturedScreen.class);verify(extractor,times(2)).extract(images.capture(),any());
    assertEquals(16,images.getAllValues().getLast().image().getWidth());
  }
  @Test void desktopResultIsNotReusedAsFreshBackgroundEvidenceOnForegroundOnlyTicks() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var time=new Time();
    when(observer.foreground()).thenReturn(new ForegroundWindow("firefox",1,"X","1",new ActivityRecord.Bounds(0,0,16,32)));
    when(observer.capture()).thenReturn(screen(10,10),screen(10,200),screen(10,50));
    when(extractor.extract(any(),any())).thenReturn(new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","social").inference(),.8));
    var store=mock(ActivityStore.class);
    var capture=new ActivityCapture(p,observer,extractor,store,mock(ScreenshotStore.class),time);
    capture.tick();time.advance(300);capture.tick();time.advance(60);capture.tick();
    var images=org.mockito.ArgumentCaptor.forClass(CapturedScreen.class);verify(extractor,times(3)).extract(images.capture(),any());
    assertEquals(32,images.getAllValues().getLast().image().getWidth());
    var records=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(store,times(3)).append(records.capture());
    assertTrue(records.getValue().duplicate());
    verify(store,times(1)).replace(any());
  }
}
