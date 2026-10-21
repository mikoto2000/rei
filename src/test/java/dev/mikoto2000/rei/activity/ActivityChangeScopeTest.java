package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.computeruse.CapturedScreen;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 作業状況変更対象範囲の結果と状態の契約を検証するテスト。 */
class ActivityChangeScopeTest {
  /** 検証条件を再現するための時刻。 */
  static class Time extends Clock {
    /** 日時や期限の条件を再現するために使う検証時刻。 */
    Instant now = Instant.parse("2026-09-23T01:00:00Z");

    /** 検証用に、作業状況の把握で参照するタイムゾーンを返す。 */
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    /** 検証用に、指定されたタイムゾーンを反映した情報を作成する。 */
    public Clock withZone(ZoneId z) {
      return this;
    }

    /** {@code instant} の条件を再現するための補助情報を返す。 */
    public Instant instant() {
      return now;
    }

    /** {@code advance} の条件を再現するため、検証用の操作や状態を扱う。 */
    void advance(int seconds) {
      now = now.plusSeconds(seconds);
    }
  }

  /** {@code screen} の条件を再現するための補助情報を返す。 */
  static CapturedScreen screen(int front, int background) {
    var image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 32; y++)
      for (int x = 0; x < 32; x++) {
        int c = x < 16 ? front : background;
        image.setRGB(x, y, (c << 16) | (c << 8) | c);
      }
    return new CapturedScreen(image, new Rectangle(0, 0, 32, 32));
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void desktopIsPeriodicRatherThanTriggeredByBackgroundMotion() throws Exception {
    var f = new DesktopContextQueueTest.Fixture();
    f.pipeline.tick();
    f.run();
    f.time.advance(60);
    f.pipeline.tick();
    assertTrue(f.tasks.isEmpty());
    verify(f.extractor, times(2)).extract(any(), any());
    f.time.advance(240);
    f.pipeline.tick();
    f.run();
    verify(f.extractor, times(4)).extract(any(), any());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void foregroundMotionImmediatelyTriggersAnalysis() throws Exception {
    var p = new ActivityProperties();
    p.setEnabled(true);
    p.getDetection().setMode(ActivityProperties.DetectionMode.VISION_FIRST);
    p.getObservation().setInputAwareEnabled(false);
    var observer = mock(DesktopActivityObserver.class);
    var extractor = mock(ActivityExtractor.class);
    when(observer.foreground())
        .thenReturn(
            new ForegroundWindow("firefox", 1, "X", "1", new ActivityRecord.Bounds(0, 0, 16, 32)));
    when(observer.capture()).thenReturn(screen(10, 10), screen(200, 10));
    when(extractor.extract(any(), any()))
        .thenReturn(
            new ActivityExtractor.Result(
                ActivityPolicyTest.record("2026-09-22T10:00:00Z", "social").inference(), .8));
    var capture =
        new ActivityCapture(
            p,
            observer,
            extractor,
            mock(ActivityStore.class),
            mock(ScreenshotStore.class),
            new Time());
    capture.tick();
    capture.tick();
    verify(extractor, times(2)).extract(any(), any());
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void foregroundChangeAloneDoesNotCountAsBackgroundChangeWhenRefreshIsDue() throws Exception {
    var p = new ActivityProperties();
    p.setEnabled(true);
    p.getDetection().setMode(ActivityProperties.DetectionMode.VISION_FIRST);
    p.getObservation().setInputAwareEnabled(false);
    var observer = mock(DesktopActivityObserver.class);
    var extractor = mock(ActivityExtractor.class);
    var time = new Time();
    when(observer.foreground())
        .thenReturn(
            new ForegroundWindow("firefox", 1, "X", "1", new ActivityRecord.Bounds(0, 0, 16, 32)));
    when(observer.capture()).thenReturn(screen(10, 10), screen(200, 10));
    when(extractor.extract(any(), any()))
        .thenReturn(
            new ActivityExtractor.Result(
                ActivityPolicyTest.record("2026-09-22T10:00:00Z", "social").inference(), .8));
    var capture =
        new ActivityCapture(
            p, observer, extractor, mock(ActivityStore.class), mock(ScreenshotStore.class), time);
    capture.tick();
    time.advance(300);
    capture.tick();
    var images = org.mockito.ArgumentCaptor.forClass(CapturedScreen.class);
    verify(extractor, times(2)).extract(images.capture(), any());
    assertEquals(16, images.getAllValues().getLast().image().getWidth());
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void desktopResultIsNotReusedAsFreshBackgroundEvidenceOnForegroundOnlyTicks() throws Exception {
    var f = new DesktopContextQueueTest.Fixture();
    f.pipeline.tick();
    f.run();
    f.time.advance(300);
    f.pipeline.tick();
    f.run();
    f.time.advance(60);
    f.pipeline.tick();
    assertTrue(f.tasks.isEmpty());
    var records = org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);
    verify(f.store, times(3)).append(records.capture());
    assertNull(records.getValue().detection().visionDiagnostics().background().context());
    assertEquals(
        VisionDiagnostics.State.NOT_ATTEMPTED,
        records.getValue().detection().visionDiagnostics().background().state());
  }
}
