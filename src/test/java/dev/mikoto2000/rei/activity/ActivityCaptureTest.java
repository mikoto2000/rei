package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.computeruse.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.awt.Rectangle;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityCaptureTest {
  ActivityProperties properties;
  DesktopActivityObserver observer;
  ActivityExtractor extractor;
  ActivityStore store;
  ScreenshotStore screenshots;
  ActivityCapture capture;
  @BeforeEach void setup() throws Exception {
    properties = new ActivityProperties(); properties.setEnabled(true);
    observer = mock(DesktopActivityObserver.class); extractor = mock(ActivityExtractor.class);
    store = mock(ActivityStore.class); screenshots = mock(ScreenshotStore.class);
    when(observer.foreground()).thenReturn(new ForegroundWindow("idea",1,"rei","1"));
    when(observer.capture()).thenReturn(screen(20));
    when(extractor.extract(any(),any())).thenReturn(new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","coding").inference(),.8));
    when(screenshots.save(anyString(),any(),any())).thenReturn(List.of("one.png","two.png"));
    capture = new ActivityCapture(properties,observer,extractor,store,screenshots,Clock.systemUTC());
  }
  @Test void disabledNeverCaptures() throws Exception { properties.setEnabled(false); capture.tick(); verify(observer,never()).capture(); }
  @Test void pauseAndResume() throws Exception { capture.pause(); capture.tick(); verify(observer,never()).capture(); capture.resume(); capture.tick(); verify(observer).capture(); }
  @Test void excludedProcessNeverCaptures() throws Exception { when(observer.foreground()).thenReturn(new ForegroundWindow("1password.exe",2,"vault","2")); capture.tick(); verify(observer,never()).capture(); verify(store,never()).append(any()); }
  @Test void excludedTitleNeverCaptures() throws Exception { when(observer.foreground()).thenReturn(new ForegroundWindow("firefox",2,"InPrivate","2")); capture.tick(); verify(observer,never()).capture(); }
  @Test void monitorEvidenceRetained() throws Exception { capture.tick(); var captor=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class); verify(store).append(captor.capture()); assertEquals(List.of("m1","m2"),captor.getValue().observations().stream().map(ActivityRecord.Observation::monitor).toList()); }
  @Test void duplicatesDoNotSaveOrExtractAgain() throws Exception { capture.tick(); capture.tick(); verify(extractor,times(1)).extract(any(),any()); verify(screenshots,times(1)).save(anyString(),any(),any()); verify(store,times(2)).append(any()); }
  @Test void changedForegroundForcesNewExtraction() throws Exception { capture.tick(); when(observer.foreground()).thenReturn(new ForegroundWindow("firefox",2,"research","2")); capture.tick(); verify(extractor,times(2)).extract(any(),any()); }
  @Test void pauseDiscardsInflightResult() throws Exception { when(extractor.extract(any(),any())).thenAnswer(invocation -> {capture.pause();return new ActivityExtractor.Result(ActivityPolicyTest.record("2026-09-22T10:00:00Z","coding").inference(),.8);}); capture.tick(); verify(store,never()).append(any()); verify(screenshots,never()).save(anyString(),any(),any()); }
  @Test void foregroundRaceDiscardsCapture() throws Exception { when(observer.foreground()).thenReturn(new ForegroundWindow("idea",1,"rei","1"),new ForegroundWindow("1password",2,"vault","2")); capture.tick(); verify(extractor,never()).extract(any(),any()); verify(screenshots,never()).save(anyString(),any(),any()); }
  @Test void failedExtractionIsIsolatedAndRetried() throws Exception { when(extractor.extract(any(),any())).thenThrow(new IllegalArgumentException("bad output")); assertDoesNotThrow(capture::tick); capture.tick(); verify(extractor,times(2)).extract(any(),any()); verify(store,never()).append(any()); }
  @Test void failedStorageIsIsolated() throws Exception { doThrow(new IllegalStateException()).when(store).append(any()); assertDoesNotThrow(capture::tick); }
  @Test void failedRetentionDoesNotStopCapture() throws Exception { doThrow(new IllegalStateException()).when(screenshots).cleanup(any()); capture.tick(); verify(store).append(any()); }
  @Test void phaseOneDoesNotCallVision() throws Exception { properties.setExtractionEnabled(false); capture.tick(); verify(extractor,never()).extract(any(),any()); verify(store).append(any()); }
  @Test void resumeCreatesSessionBoundary() throws Exception {
    capture.tick();capture.pause();capture.resume();capture.tick();
    var records=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);verify(store,times(2)).append(records.capture());
    assertEquals(2,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC).aggregate(records.getAllValues()).size());
  }
  static CapturedScreen screen(int gray) {
    var bounds = new Rectangle(0,0,32,32);
    return new CapturedScreen(List.of(new DisplayCapture(new ScreenGeometry("m1",bounds,bounds,true,1,1),ActivityPolicyTest.image(gray)),new DisplayCapture(new ScreenGeometry("m2",bounds,bounds,false,1,1),ActivityPolicyTest.image(gray))));
  }
}
