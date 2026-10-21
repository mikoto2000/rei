package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 作業状況根拠安全性の結果と状態の契約を検証するテスト。 */
class ActivityEvidenceSafetyTest {
  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void unknownForegroundBoundsDoNotSilentlySendFullDesktop() throws Exception {
    var p = new ActivityProperties();
    p.setEnabled(true);
    var observer = mock(DesktopActivityObserver.class);
    var extractor = mock(ActivityExtractor.class);
    var store = mock(ActivityStore.class);
    when(observer.foreground())
        .thenReturn(new ForegroundWindow("Firefox", 1, "Mozilla Firefox", "1"));
    when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(10, 20));
    new ActivityCapture(
            p,
            observer,
            extractor,
            store,
            mock(ScreenshotStore.class),
            new ActivityChangeScopeTest.Time())
        .tick();
    verify(store).append(any());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(extractor);
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void optInBackgroundHasOneWorkerAndEnrichesOriginalObservation() throws Exception {
    var f = new DesktopContextQueueTest.Fixture();
    f.pipeline.tick();
    f.time.advance(60);
    f.change("B");
    f.pipeline.tick();
    assertEquals(1, f.tasks.size());
    verify(f.store, times(2)).append(any());
    f.run();
    var records = org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);
    verify(f.store, times(2)).append(records.capture());
    verify(f.store)
        .replace(
            argThat(
                r ->
                    r.id().equals(records.getAllValues().getFirst().id())
                        && r.detection().visionDiagnostics().background().state()
                            == VisionDiagnostics.State.USED));
  }

  /** 無効状態代替手段または画像認識引き続きについて、不明観測内容を保存することを確認する。 */
  @Test
  void disabledFallbackOrVisionStillStoresUnknownObservationWithoutPixels() throws Exception {
    for (boolean disableVision : List.of(false, true)) {
      var p = new ActivityProperties();
      p.setEnabled(true);
      if (disableVision) p.getDetection().setVisionEnabled(false);
      else p.getDetection().setFallbackEnabled(false);
      var observer = mock(DesktopActivityObserver.class);
      var extractor = mock(ActivityExtractor.class);
      var store = mock(ActivityStore.class);
      when(observer.foreground())
          .thenReturn(ActivityEvidenceClassifierTest.window("Firefox", "Mozilla Firefox"));
      new ActivityCapture(
              p,
              observer,
              extractor,
              store,
              mock(ScreenshotStore.class),
              new ActivityChangeScopeTest.Time())
          .tick();
      verify(store).append(any());
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(extractor);
      verify(observer, never()).capture();
    }
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void screenshotFailureDoesNotEraseOsObservation() throws Exception {
    var p = new ActivityProperties();
    p.setEnabled(true);
    var observer = mock(DesktopActivityObserver.class);
    var store = mock(ActivityStore.class);
    var extractor = mock(ActivityExtractor.class);
    when(observer.foreground())
        .thenReturn(ActivityEvidenceClassifierTest.window("Firefox", "Mozilla Firefox"));
    when(observer.capture()).thenThrow(new java.io.IOException());
    new ActivityCapture(
            p,
            observer,
            extractor,
            store,
            mock(ScreenshotStore.class),
            new ActivityChangeScopeTest.Time())
        .tick();
    verify(store).append(any());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(extractor);
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void backgroundMotionNeverTriggersDefaultVisionEvenAfterInterval() throws Exception {
    var p = new ActivityProperties();
    p.setEnabled(true);
    var observer = mock(DesktopActivityObserver.class);
    var extractor = mock(ActivityExtractor.class);
    var store = mock(ActivityStore.class);
    when(observer.foreground())
        .thenReturn(ActivityEvidenceClassifierTest.window("Firefox", "ホーム / X"));
    var time = new ActivityChangeScopeTest.Time();
    var backgrounds = new ArrayList<Runnable>();
    var capture =
        new ActivityCapture(
            p,
            observer,
            extractor,
            store,
            mock(ScreenshotStore.class),
            time,
            Runnable::run,
            backgrounds::add);
    for (int i = 0; i < 8; i++) {
      capture.tick();
      time.advance(60);
    }
    verify(store, times(8)).append(any());
    verify(observer, never()).capture();
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(extractor);
    assertTrue(backgrounds.isEmpty());
  }

  /** 遅れて届いた結果を受け付けないことを確認する。 */
  @Test
  void observationContinuesWhileVisionIsActuallyBlockedAndPauseRejectsLateResult()
      throws Exception {
    var p = new ActivityProperties();
    p.setEnabled(true);
    var observer = mock(DesktopActivityObserver.class);
    var extractor = mock(ActivityExtractor.class);
    var store = mock(ActivityStore.class);
    when(observer.foreground())
        .thenReturn(ActivityEvidenceClassifierTest.window("Firefox", "Mozilla Firefox"));
    when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(10, 20));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(extractor.extract(any(), any()))
        .thenAnswer(
            i -> {
              entered.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return new ActivityExtractor.Result(
                  ActivityPolicyTest.record("2026-09-22T10:00:00Z", "social").inference(), .9);
            });
    var time = new ActivityChangeScopeTest.Time();
    try (var worker = Executors.newSingleThreadExecutor()) {
      var capture =
          new ActivityCapture(
              p,
              observer,
              extractor,
              store,
              mock(ScreenshotStore.class),
              time,
              worker,
              Runnable::run);
      try {
        capture.tick();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 3; i++) {
          time.advance(60);
          capture.tick();
        }
        verify(store, times(4)).append(any());
        verify(store)
            .replace(
                argThat(
                    r ->
                        r.detection().visionDiagnostics().foreground().state()
                            == VisionDiagnostics.State.ATTEMPTED));
        clearInvocations(store);
        capture.pause();
        release.countDown();
        worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
        verify(extractor).extract(any(), any());
        verify(store, never()).replace(any());
      } finally {
        release.countDown();
        capture.close();
      }
    }
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void optionalSourceFailureAndPrivateInvisibleWindowsAreIsolated() {
    var p = new ActivityProperties();
    var fg = ActivityEvidenceClassifierTest.window("Code", "A.java - rei - Visual Studio Code");
    var privateWindow = new ForegroundWindow("Firefox", 2, "Enter Password", "2");
    var social = new ForegroundWindow("Firefox", 3, "ホーム / X", "3");
    var windows =
        List.of(
            new ActivityEvidence.VisibleWindow(privateWindow, true, false, false, "m1"),
            new ActivityEvidence.VisibleWindow(social, false, false, false, "m1"),
            new ActivityEvidence.VisibleWindow(social, true, true, false, "m1"),
            new ActivityEvidence.VisibleWindow(social, true, false, true, "m1"));
    var aggregator =
        new ActivityEvidenceAggregator(
            p,
            List.of(
                at -> {
                  throw new IllegalStateException("private");
                },
                at -> new ActivityEvidenceSource.Contribution("rei", "p", List.of())));
    var evidence =
        aggregator.collect(Instant.now(), new DesktopActivityObserver.Metadata(fg, windows), null);
    assertTrue(evidence.visibleWindows().isEmpty());
    assertEquals("rei", evidence.projectName());
    assertEquals(
        "development",
        new ActivityClassifier().classify(evidence).inference().activities().getFirst().type());
  }

  /** 作業状況の把握で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void foregroundExclusionBlocksMetadataPersistenceAndVision() throws Exception {
    var p = new ActivityProperties();
    p.setEnabled(true);
    var observer = mock(DesktopActivityObserver.class);
    var store = mock(ActivityStore.class);
    var extractor = mock(ActivityExtractor.class);
    when(observer.foreground())
        .thenReturn(new ForegroundWindow("Firefox", 1, "Enter Password", "1"));
    new ActivityCapture(
            p,
            observer,
            extractor,
            store,
            mock(ScreenshotStore.class),
            new ActivityChangeScopeTest.Time())
        .tick();
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(store, extractor);
    verify(observer, never()).capture();
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void projectAndRecentToolTogetherCanIdentifyTerminalButHistoryAloneCannot() {
    var at = Instant.now();
    var fg = new ForegroundWindow("WindowsTerminal", 1, "rei", "1");
    var evidence =
        new ActivityEvidence(
            at,
            fg,
            List.of(),
            "rei",
            "p",
            List.of(new ActivityEvidence.RecentEvent(at, "p", "SHELL")),
            null);
    assertEquals(.9, new ActivityClassifier().classify(evidence).confidence());
    var history =
        new ActivityEvidence.History(at, fg, new ActivityRecord.Inference("old", List.of()), .99);
    assertEquals(
        0,
        new ActivityClassifier()
            .classify(new ActivityEvidence(at, fg, List.of(), "rei", "p", List.of(), history))
            .confidence());
  }

  /** 不正な判定の境界値を受け付けないことを確認する。 */
  @Test
  void configBindsDefaultsAndRejectsInvalidThresholds() {
    var defaults = new ActivityProperties();
    assertEquals(
        ActivityProperties.DetectionMode.EVIDENCE_FIRST, defaults.getDetection().getMode());
    assertEquals(.8, defaults.getDetection().getSkipVisionConfidence());
    assertFalse(defaults.getDetection().isBackgroundFullScreenEnabled());
    assertEquals(2048, defaults.getDetection().getMaxOutputTokens());
    var binder =
        new org.springframework.boot.context.properties.bind.Binder(
            new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                Map.of(
                    "rei.activity.detection.mode",
                    "vision-first",
                    "rei.activity.detection.max-output-tokens",
                    "512")));
    var p = binder.bind("rei.activity", ActivityProperties.class).get();
    assertEquals(ActivityProperties.DetectionMode.VISION_FIRST, p.getDetection().getMode());
    assertEquals(512, p.getDetection().getMaxOutputTokens());
    for (double value : new double[] {-.1, 1.1, Double.NaN}) {
      p.getDetection().setSkipVisionConfidence(value);
      assertThrows(IllegalArgumentException.class, p::validate);
    }
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void skipThresholdIsInclusive() throws Exception {
    for (double threshold : new double[] {.95, .96}) {
      var p = new ActivityProperties();
      p.setEnabled(true);
      p.getDetection().setSkipVisionConfidence(threshold);
      var observer = mock(DesktopActivityObserver.class);
      var extractor = mock(ActivityExtractor.class);
      when(observer.foreground())
          .thenReturn(ActivityEvidenceClassifierTest.window("Firefox", "ホーム / X"));
      when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(10, 20));
      var tasks = new ArrayList<Runnable>();
      new ActivityCapture(
              p,
              observer,
              extractor,
              mock(ActivityStore.class),
              mock(ScreenshotStore.class),
              new ActivityChangeScopeTest.Time(),
              tasks::add,
              Runnable::run)
          .tick();
      assertEquals(threshold == .95 ? 0 : 1, tasks.size());
    }
  }
}
