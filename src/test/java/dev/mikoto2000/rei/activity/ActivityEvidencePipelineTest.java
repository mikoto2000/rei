package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 作業状況根拠処理の連携の結果と状態の契約を検証するテスト。 */
class ActivityEvidencePipelineTest {
  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void codeAndXSaveWithoutScreenshotOrVision() throws Exception {
    for (var fg :
        List.of(
            ActivityEvidenceClassifierTest.window(
                "Code.exe", "BehaviorEvaluator.java - rei - Visual Studio Code"),
            ActivityEvidenceClassifierTest.window("Firefox", "ホーム / X"))) {
      var p = new ActivityProperties();
      p.setEnabled(true);
      var observer = mock(DesktopActivityObserver.class);
      var extractor = mock(ActivityExtractor.class);
      var store = mock(ActivityStore.class);
      when(observer.foreground()).thenReturn(fg);
      var capture =
          new ActivityCapture(
              p,
              observer,
              extractor,
              store,
              mock(ScreenshotStore.class),
              new ActivityChangeScopeTest.Time());
      capture.tick();
      var records = org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);
      verify(store).append(records.capture());
      assertFalse(records.getValue().detection().visionUsed());
      assertEquals("EVIDENCE_ONLY", records.getValue().detection().classificationMode());
      verify(observer, never()).capture();
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(extractor);
    }
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void pendingReplacementAndFailureNeverLoseEvidence() throws Exception {
    var p = new ActivityProperties();
    p.setEnabled(true);
    var observer = mock(DesktopActivityObserver.class);
    var extractor = mock(ActivityExtractor.class);
    var store = mock(ActivityStore.class);
    when(observer.foreground())
        .thenReturn(ActivityEvidenceClassifierTest.window("Firefox", "Mozilla Firefox"));
    when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(20, 20));
    when(extractor.extract(any(), any()))
        .thenThrow(new java.net.SocketTimeoutException("private payload"));
    var tasks = new ArrayList<Runnable>();
    var background = new ArrayList<Runnable>();
    var time = new ActivityChangeScopeTest.Time();
    var capture =
        new ActivityCapture(
            p,
            observer,
            extractor,
            store,
            mock(ScreenshotStore.class),
            time,
            tasks::add,
            background::add);
    for (int i = 0; i < 4; i++) {
      capture.tick();
      time.advance(60);
    }
    verify(store, times(4)).append(any());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(extractor);
    assertEquals(1, tasks.size());
    assertTrue(background.isEmpty());
    tasks.getFirst().run();
    verify(extractor).extract(any(), any());
    verify(store, times(4)).append(any());
  }

  /** 作業状況の把握で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void fallbackPersistsFirstAndEnrichesTheSameIdWithCrop() throws Exception {
    var p = new ActivityProperties();
    p.setEnabled(true);
    var observer = mock(DesktopActivityObserver.class);
    var extractor = mock(ActivityExtractor.class);
    var store = mock(ActivityStore.class);
    when(observer.foreground())
        .thenReturn(
            new ForegroundWindow(
                "Firefox", 1, "Mozilla Firefox", "1", new ActivityRecord.Bounds(0, 0, 16, 32)));
    when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(20, 200));
    when(extractor.extract(any(), any()))
        .thenAnswer(
            i -> {
              verify(store).append(any());
              assertEquals(
                  16,
                  ((dev.mikoto2000.rei.computeruse.CapturedScreen) i.getArgument(0))
                      .image()
                      .getWidth());
              return new ActivityExtractor.Result(
                  new ActivityRecord.Inference(
                      "visible",
                      List.of(
                          new ActivityRecord.Activity(
                              "primary", "social", "Firefox", "X", "", ""))),
                  .9);
            });
    var capture =
        new ActivityCapture(
            p,
            observer,
            extractor,
            store,
            mock(ScreenshotStore.class),
            new ActivityChangeScopeTest.Time());
    capture.tick();
    var original = org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);
    var updated = org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);
    verify(store).append(original.capture());
    verify(store, atLeastOnce()).replace(updated.capture());
    assertEquals(original.getValue().id(), updated.getValue().id());
    assertEquals(60, updated.getValue().durationEstimate());
    assertTrue(updated.getValue().detection().visionUsed());
    assertEquals("social", updated.getValue().inference().activities().getFirst().type());
  }
}
