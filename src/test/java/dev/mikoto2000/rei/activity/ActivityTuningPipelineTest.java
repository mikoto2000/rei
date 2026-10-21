package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 作業状況Tuning処理の連携の結果と状態の契約を検証するテスト。 */
class ActivityTuningPipelineTest {
  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void conflictingOrUnknownVisionDoesNotEraseStrongerUsableEvidence() throws Exception {
    for (var result :
        List.of(
            new ActivityExtractor.Result(
                new ActivityRecord.Inference(
                    "unknown",
                    List.of(new ActivityRecord.Activity("m", "unknown", "Firefox", "", "", ""))),
                .99),
            new ActivityExtractor.Result(
                new ActivityRecord.Inference(
                    "different service",
                    List.of(
                        new ActivityRecord.Activity("m", "shopping", "Firefox", "Amazon", "", ""))),
                .9))) {
      var p = new ActivityProperties();
      p.setEnabled(true);
      p.getDetection().setSkipVisionConfidence(1);
      var observer = mock(DesktopActivityObserver.class);
      var extractor = mock(ActivityExtractor.class);
      var store = mock(ActivityStore.class);
      when(observer.foreground()).thenReturn(ActivityEvidenceClassifierTest.window("Firefox", "X"));
      when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(10, 20));
      when(extractor.extract(any(), any())).thenReturn(result);
      new ActivityCapture(
              p,
              observer,
              extractor,
              store,
              mock(ScreenshotStore.class),
              new ActivityChangeScopeTest.Time())
          .tick();
      var saved = org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);
      verify(store, times(2)).replace(saved.capture());
      assertEquals("social", saved.getValue().inference().activities().getFirst().type());
      assertEquals("X", saved.getValue().inference().activities().getFirst().service());
      assertEquals(.95, saved.getValue().confidence());
    }
  }

  /** 作業状況の把握で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void knownCategoryWithMissingProjectAndContentSkipsVisionAndPersistsAxes() throws Exception {
    var p = new ActivityProperties();
    p.setEnabled(true);
    var observer = mock(DesktopActivityObserver.class);
    var extractor = mock(ActivityExtractor.class);
    var store = mock(ActivityStore.class);
    when(observer.foreground())
        .thenReturn(ActivityEvidenceClassifierTest.window("Firefox", "YouTube"));
    new ActivityCapture(
            p,
            observer,
            extractor,
            store,
            mock(ScreenshotStore.class),
            new ActivityChangeScopeTest.Time())
        .tick();
    var saved = org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);
    verify(store).append(saved.capture());
    assertTrue(saved.getValue().detection().fieldConfidence().usable(.8));
    assertFalse(saved.getValue().detection().fieldConfidence().complete());
    assertEquals(0, saved.getValue().detection().fieldConfidence().content());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(extractor);
    verify(observer, never()).capture();
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void visionEnrichesCategoryWithoutErasingKnownServiceOrAddingObservation() throws Exception {
    var p = new ActivityProperties();
    p.setEnabled(true);
    var observer = mock(DesktopActivityObserver.class);
    var extractor = mock(ActivityExtractor.class);
    var store = mock(ActivityStore.class);
    when(observer.foreground())
        .thenReturn(ActivityEvidenceClassifierTest.window("Firefox", "ChatGPT"));
    when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(10, 20));
    when(extractor.extract(any(), any()))
        .thenReturn(
            new ActivityExtractor.Result(
                new ActivityRecord.Inference(
                    "調査",
                    List.of(new ActivityRecord.Activity("primary", "research", "", "", "", ""))),
                .9));
    new ActivityCapture(
            p,
            observer,
            extractor,
            store,
            mock(ScreenshotStore.class),
            new ActivityChangeScopeTest.Time())
        .tick();
    var initial = org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);
    var updated = org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);
    verify(store).append(initial.capture());
    verify(store, times(2)).replace(updated.capture());
    assertEquals(initial.getValue().id(), updated.getValue().id());
    assertEquals(60, updated.getValue().durationEstimate());
    var a = updated.getValue().inference().activities().getFirst();
    assertEquals("research", a.type());
    assertEquals("ChatGPT", a.service());
    assertEquals("Firefox", a.application());
    assertEquals(.9, updated.getValue().detection().fieldConfidence().category());
    assertEquals(.95, updated.getValue().detection().fieldConfidence().service());
  }

  /** 出力上限タイムアウトおよび検証について、部分的な結果根拠を維持することを確認する。 */
  @Test
  void outputLimitTimeoutAndValidationKeepPartialEvidence() throws Exception {
    for (Exception error :
        List.of(
            new ActivityOutputParser.InvalidOutput(
                List.of(new ActivityOutputParser.ResultError("/", "output_limit"))),
            new java.net.SocketTimeoutException(),
            new ActivityOutputParser.InvalidOutput(
                List.of(new ActivityOutputParser.ResultError("/", "invalid_json"))))) {
      var p = new ActivityProperties();
      p.setEnabled(true);
      var observer = mock(DesktopActivityObserver.class);
      var extractor = mock(ActivityExtractor.class);
      var store = mock(ActivityStore.class);
      when(observer.foreground())
          .thenReturn(ActivityEvidenceClassifierTest.window("Firefox", "ChatGPT"));
      when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(10, 20));
      when(extractor.extract(any(), any())).thenThrow(error);
      new ActivityCapture(
              p,
              observer,
              extractor,
              store,
              mock(ScreenshotStore.class),
              new ActivityChangeScopeTest.Time())
          .tick();
      verify(store).append(any());
      verify(extractor).extract(any(), any());
      var saved = org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);
      verify(store, times(2)).replace(saved.capture());
      assertEquals("ChatGPT", saved.getValue().inference().activities().getFirst().service());
      assertEquals("unknown", saved.getValue().inference().activities().getFirst().type());
      assertTrue(saved.getValue().detection().fieldConfidence().partial());
      assertEquals("VISION_FAILED", saved.getValue().detection().status());
    }
  }

  /** 作業状況の把握で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void failureTaxonomyDistinguishesLimitTimeoutAndValidation() {
    assertEquals(
        ActivityVisionFailure.OUTPUT_LIMIT,
        ActivityVisionFailure.classify(
            new ActivityOutputParser.InvalidOutput(
                List.of(new ActivityOutputParser.ResultError("/", "output_limit")))));
    assertEquals(
        ActivityVisionFailure.TIMEOUT,
        ActivityVisionFailure.classify(
            new RuntimeException(new java.net.SocketTimeoutException())));
    assertEquals(
        ActivityVisionFailure.VALIDATION,
        ActivityVisionFailure.classify(
            new ActivityOutputParser.InvalidOutput(
                List.of(new ActivityOutputParser.ResultError("/", "invalid_json")))));
  }
}
