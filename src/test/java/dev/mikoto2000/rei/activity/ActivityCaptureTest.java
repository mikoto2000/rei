package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.computeruse.CapturedScreen;
import dev.mikoto2000.rei.computeruse.DisplayCapture;
import dev.mikoto2000.rei.computeruse.ScreenGeometry;
import java.awt.Rectangle;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 作業状況取得記録の結果と状態の契約を検証するテスト。 */
class ActivityCaptureTest {
  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void validationFailureLogsSanitizedDiagnostic() throws Exception {
    when(extractor.extract(any(), any()))
        .thenThrow(
            new ActivityOutputParser.InvalidOutput(
                List.of(new ActivityOutputParser.ResultError("/confidence", "maximum"))));
    var logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ActivityCapture.class);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      capture.tick();
      assertTrue(
          appender.list.stream()
              .anyMatch(e -> e.getFormattedMessage().contains("/confidence:maximum")));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  /** 検証に使うこの機能の動作設定。 */
  ActivityProperties properties;

  /** 検証に使う観測。 */
  DesktopActivityObserver observer;

  /** 検証に使う情報の抽出。 */
  ActivityExtractor extractor;

  /** 検証に使う保存先。 */
  ActivityStore store;

  /** 検証に使う画面画像一覧。 */
  ScreenshotStore screenshots;

  /** 検証に使う取得記録。 */
  ActivityCapture capture;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() throws Exception {
    properties = new ActivityProperties();
    properties.setEnabled(true);
    properties.getDetection().setMode(ActivityProperties.DetectionMode.VISION_FIRST);
    properties.getObservation().setInputAwareEnabled(false);
    observer = mock(DesktopActivityObserver.class);
    extractor = mock(ActivityExtractor.class);
    store = mock(ActivityStore.class);
    screenshots = mock(ScreenshotStore.class);
    when(observer.foreground()).thenReturn(new ForegroundWindow("idea", 1, "rei", "1"));
    when(observer.capture()).thenReturn(screen(20));
    when(extractor.extract(any(), any()))
        .thenReturn(
            new ActivityExtractor.Result(
                ActivityPolicyTest.record("2026-09-22T10:00:00Z", "coding").inference(), .8));
    when(screenshots.save(anyString(), any(), any())).thenReturn(List.of("one.png", "two.png"));
    capture =
        new ActivityCapture(properties, observer, extractor, store, screenshots, Clock.systemUTC());
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void disabledNeverCaptures() throws Exception {
    properties.setEnabled(false);
    capture.tick();
    verify(observer, never()).capture();
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void pauseAndResume() throws Exception {
    capture.pause();
    capture.tick();
    verify(observer, never()).capture();
    capture.resume();
    capture.tick();
    verify(observer).capture();
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void excludedProcessNeverCaptures() throws Exception {
    when(observer.foreground()).thenReturn(new ForegroundWindow("1password.exe", 2, "vault", "2"));
    capture.tick();
    verify(observer, never()).capture();
    verify(store, never()).append(any());
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void excludedTitleNeverCaptures() throws Exception {
    when(observer.foreground()).thenReturn(new ForegroundWindow("firefox", 2, "InPrivate", "2"));
    capture.tick();
    verify(observer, never()).capture();
  }

  /** 作業状況の把握で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void monitorEvidenceRetained() throws Exception {
    capture.tick();
    var captor = org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);
    verify(store).append(captor.capture());
    assertEquals(
        List.of("m1", "m2"),
        captor.getValue().observations().stream()
            .map(ActivityRecord.Observation::monitor)
            .toList());
  }

  /** 作業状況の把握で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void duplicatesDoNotSaveOrExtractAgain() throws Exception {
    properties.setKeepScreenshots(true);
    capture.tick();
    capture.tick();
    verify(extractor, times(1)).extract(any(), any());
    verify(screenshots, times(1)).save(anyString(), any(), any());
    verify(store, times(2)).append(any());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void changedImageDefaultsToNoDiskWrite() throws Exception {
    capture.tick();
    verify(extractor).extract(any(), any());
    verify(screenshots, never()).save(anyString(), any(), any());
    var record = org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);
    verify(store).append(record.capture());
    assertTrue(record.getValue().screenshotReferences().isEmpty());
  }

  /** 重複の有無定期確認について、なし画像を書き込むことを確認する。 */
  @Test
  void duplicateTickWritesNoImageAndCallsNoVisionEvenWithEvidenceEnabled() throws Exception {
    properties.setKeepScreenshots(true);
    capture.tick();
    clearInvocations(screenshots, extractor);
    capture.tick();
    verify(screenshots, never()).save(anyString(), any(), any());
    verifyNoInteractions(extractor);
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void evidenceIsSavedOnlyAfterExtractionCompletes() throws Exception {
    properties.setKeepScreenshots(true);
    when(observer.capture())
        .thenAnswer(
            i -> {
              verify(screenshots, never()).save(anyString(), any(), any());
              return screen(20);
            });
    when(extractor.extract(any(), any()))
        .thenAnswer(
            i -> {
              verify(screenshots, never()).save(anyString(), any(), any());
              return new ActivityExtractor.Result(
                  ActivityPolicyTest.record("2026-09-22T10:00:00Z", "coding").inference(), .8);
            });
    capture.tick();
    var order = inOrder(extractor, screenshots, store);
    order.verify(extractor).extract(any(), any());
    order.verify(screenshots).save(anyString(), any(), any());
    order.verify(store).append(any());
  }

  /** 根拠失敗情報引き続きについて、有効な記録を保存することを確認する。 */
  @Test
  void evidenceFailureStillSavesValidRecord() throws Exception {
    properties.setKeepScreenshots(true);
    when(screenshots.save(anyString(), any(), any()))
        .thenThrow(new java.io.IOException("disk full"));
    assertDoesNotThrow(capture::tick);
    var record = org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);
    verify(store).append(record.capture());
    assertTrue(record.getValue().screenshotReferences().isEmpty());
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void extractionFailureDefaultsToNoEvidence() throws Exception {
    when(extractor.extract(any(), any())).thenThrow(new java.io.IOException("vision"));
    capture.tick();
    verify(screenshots, never()).save(anyString(), any(), any());
    verifyNoInteractions(store);
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void successFlagDoesNotEnableFailureEvidence() throws Exception {
    properties.setKeepScreenshots(true);
    when(extractor.extract(any(), any())).thenThrow(new IllegalArgumentException("invalid JSON"));
    capture.tick();
    verify(screenshots, never()).save(anyString(), any(), any());
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void extractionFailureEvidenceIsOptIn() throws Exception {
    properties.setKeepOnExtractionFailure(true);
    when(extractor.extract(any(), any())).thenThrow(new java.io.IOException("vision"));
    capture.tick();
    verify(screenshots).save(anyString(), any(), any());
    verifyNoInteractions(store);
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failureFlagDoesNotSaveSuccessfulImages() throws Exception {
    properties.setKeepOnExtractionFailure(true);
    capture.tick();
    verify(screenshots, never()).save(anyString(), any(), any());
    verify(store).append(any());
  }

  /** 失敗情報根拠について、失敗情報独立したを書き込むことを確認する。 */
  @Test
  void failureEvidenceWriteFailureIsIsolated() throws Exception {
    properties.setKeepOnExtractionFailure(true);
    when(extractor.extract(any(), any())).thenThrow(new java.io.IOException("encode"));
    when(screenshots.save(anyString(), any(), any())).thenThrow(new java.io.IOException("disk"));
    assertDoesNotThrow(capture::tick);
    verifyNoInteractions(store);
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void exclusionOverridesBothEvidenceFlags() throws Exception {
    properties.setKeepScreenshots(true);
    properties.setKeepOnExtractionFailure(true);
    when(observer.foreground()).thenReturn(new ForegroundWindow("1password", 2, "Password", "2"));
    capture.tick();
    verify(observer, never()).capture();
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(extractor, store);
    verify(screenshots, never()).save(anyString(), any(), any());
  }

  /** 作業状況の把握で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void foregroundRaceNeverSavesFailureEvidence() throws Exception {
    properties.setKeepScreenshots(true);
    properties.setKeepOnExtractionFailure(true);
    when(observer.foreground())
        .thenReturn(
            new ForegroundWindow("idea", 1, "rei", "1"),
            new ForegroundWindow("firefox", 2, "InPrivate", "2"));
    capture.tick();
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(extractor, store);
    verify(screenshots, never()).save(anyString(), any(), any());
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void pauseDuringFailureDiscardsEvidence() throws Exception {
    properties.setKeepOnExtractionFailure(true);
    when(extractor.extract(any(), any()))
        .thenAnswer(
            i -> {
              capture.pause();
              throw new java.io.IOException("vision");
            });
    capture.tick();
    verify(screenshots, never()).save(anyString(), any(), any());
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void captureFailureNeverBecomesExtractionEvidence() throws Exception {
    properties.setKeepOnExtractionFailure(true);
    when(observer.capture()).thenThrow(new java.io.IOException("capture"));
    capture.tick();
    verify(screenshots, never()).save(anyString(), any(), any());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void zeroRetentionOverridesAllEvidenceFlags() throws Exception {
    properties.setKeepScreenshots(true);
    properties.setKeepOnExtractionFailure(true);
    properties.setScreenshotRetentionDays(0);
    capture.tick();
    when(extractor.extract(any(), any())).thenThrow(new java.io.IOException());
    when(observer.capture()).thenReturn(screen(200));
    capture.tick();
    verify(screenshots, never()).save(anyString(), any(), any());
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failureLogsNeverIncludeProviderPayloadOrImageData() throws Exception {
    properties.setKeepOnExtractionFailure(true);
    String privatePayload = "data:image/png;base64,PRIVATE_PROVIDER_PAYLOAD";
    when(extractor.extract(any(), any())).thenThrow(new java.io.IOException(privatePayload));
    when(screenshots.save(anyString(), any(), any()))
        .thenThrow(new java.io.IOException(privatePayload));
    var logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ActivityCapture.class);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      capture.tick();
      assertFalse(appender.list.isEmpty());
      for (var event : appender.list) {
        assertFalse(event.getFormattedMessage().contains(privatePayload));
        assertNull(event.getThrowableProxy());
      }
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void changedForegroundForcesNewExtraction() throws Exception {
    capture.tick();
    when(observer.foreground()).thenReturn(new ForegroundWindow("firefox", 2, "research", "2"));
    capture.tick();
    verify(extractor, times(2)).extract(any(), any());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void pauseDiscardsInflightResult() throws Exception {
    when(extractor.extract(any(), any()))
        .thenAnswer(
            invocation -> {
              capture.pause();
              return new ActivityExtractor.Result(
                  ActivityPolicyTest.record("2026-09-22T10:00:00Z", "coding").inference(), .8);
            });
    capture.tick();
    verify(store, never()).append(any());
    verify(screenshots, never()).save(anyString(), any(), any());
  }

  /** 作業状況の把握で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void foregroundRaceDiscardsCapture() throws Exception {
    when(observer.foreground())
        .thenReturn(
            new ForegroundWindow("idea", 1, "rei", "1"),
            new ForegroundWindow("1password", 2, "vault", "2"));
    capture.tick();
    verify(extractor, never()).extract(any(), any());
    verify(screenshots, never()).save(anyString(), any(), any());
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failedExtractionIsIsolatedAndRetried() throws Exception {
    when(extractor.extract(any(), any())).thenThrow(new IllegalArgumentException("bad output"));
    assertDoesNotThrow(capture::tick);
    capture.tick();
    verify(extractor, times(2)).extract(any(), any());
    verify(store, never()).append(any());
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failedStorageIsIsolated() throws Exception {
    doThrow(new IllegalStateException()).when(store).append(any());
    assertDoesNotThrow(capture::tick);
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failedRetentionDoesNotStopCapture() throws Exception {
    doThrow(new IllegalStateException()).when(screenshots).cleanup(any());
    capture.tick();
    verify(store).append(any());
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void phaseOneDoesNotCallVision() throws Exception {
    properties.setExtractionEnabled(false);
    capture.tick();
    verify(extractor, never()).extract(any(), any());
    verify(store).append(any());
  }

  /** 再開について、会話境界条件を作成することを確認する。 */
  @Test
  void resumeCreatesSessionBoundary() throws Exception {
    capture.tick();
    capture.pause();
    capture.resume();
    capture.tick();
    var records = org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);
    verify(store, times(2)).append(records.capture());
    assertEquals(
        2,
        new SessionMergePolicy(Duration.ofSeconds(90), ZoneOffset.UTC)
            .aggregate(records.getAllValues())
            .size());
  }

  /** {@code screen} の条件を再現するための補助情報を返す。 */
  static CapturedScreen screen(int gray) {
    var bounds = new Rectangle(0, 0, 32, 32);
    return new CapturedScreen(
        List.of(
            new DisplayCapture(
                new ScreenGeometry("m1", bounds, bounds, true, 1, 1),
                ActivityPolicyTest.image(gray)),
            new DisplayCapture(
                new ScreenGeometry("m2", bounds, bounds, false, 1, 1),
                ActivityPolicyTest.image(gray))));
  }
}
