package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** デスクトップ文脈実行待ちの結果と状態の契約を検証するテスト。 */
class DesktopContextQueueTest {
  /** 検証条件を再現するための検証条件。 */
  static class Fixture extends ActivityVisionQueueTest.Fixture {
    /** 検証条件に必要な値を受け取り、Fixtureを用意する。 */
    Fixture() throws Exception {
      p.getDetection().setBackgroundFullScreenEnabled(true);
      foreground =
          new ForegroundWindow(
              "Firefox", 1, "Mozilla Firefox", "1", new ActivityRecord.Bounds(0, 0, 16, 32));
      when(observer.metadata())
          .thenAnswer(i -> new DesktopActivityObserver.Metadata(foreground, List.of(), true));
    }

    /** {@code change} の条件を再現するため、検証用の操作や状態を扱う。 */
    @Override
    void change(String title) {
      foreground =
          new ForegroundWindow(
              "Firefox",
              1,
              "Mozilla Firefox " + title,
              "1",
              new ActivityRecord.Bounds(0, 0, 16, 32));
    }
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void foregroundAndBackgroundShareOneWorkerAndForegroundWins() throws Exception {
    var f = new Fixture();
    f.pipeline.tick();
    assertEquals(1, f.tasks.size());
    assertEquals(1, f.pipeline.desktopPending());
    var scopes = new ArrayList<String>();
    when(f.extractor.extract(any(), any()))
        .thenAnswer(
            i -> {
              scopes.add(org.slf4j.MDC.get("activityScope"));
              if (scopes.size() == 1) {
                f.time.advance(30);
                f.change("B");
                f.pipeline.tick();
                assertEquals(0, f.tasks.size());
              }
              return new ActivityExtractor.Result(
                  ActivityPolicyTest.record("2026-09-22T10:00:00Z", "social").inference(), .9);
            });
    f.run();
    assertEquals(List.of("foreground", "foreground", "background"), scopes);
    assertEquals(0, f.pipeline.desktopPending());
  }

  /** 前面を維持することを確認する。 */
  @Test
  void idleAndMissingEnumerationSuppressDesktopButKeepForeground() throws Exception {
    var f = new Fixture();
    when(f.observer.lightweight())
        .thenReturn(new DesktopActivityObserver.Lightweight(0, 90000, "1", "Default", false, true));
    f.pipeline.tick();
    f.run();
    verify(f.extractor).extract(any(), any());
    f.time.advance(300);
    f.change("new");
    when(f.observer.metadata())
        .thenAnswer(i -> new DesktopActivityObserver.Metadata(f.foreground, List.of()));
    f.pipeline.tick();
    assertTrue(f.tasks.isEmpty());
    verify(f.extractor).extract(any(), any());
    verify(f.store, times(2)).append(any());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void staleDesktopIsDiscardedAndShutdownDropsBothSlots() throws Exception {
    var f = new Fixture();
    f.pipeline.tick();
    f.time.advance(301);
    f.run();
    verify(f.extractor).extract(any(), any());
    f.time.advance(300);
    f.change("B");
    f.pipeline.tick();
    f.pipeline.close();
    f.run();
    verify(f.extractor).extract(any(), any());
    assertEquals(0, f.pipeline.desktopPending());
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failedDesktopLeavesMainRecordAndWorkerAvailable() throws Exception {
    var f = new Fixture();
    when(f.extractor.extract(any(), any()))
        .thenAnswer(
            i -> {
              if ("background".equals(org.slf4j.MDC.get("activityScope")))
                throw new java.io.IOException();
              return new ActivityExtractor.Result(
                  ActivityPolicyTest.record("2026-09-22T10:00:00Z", "social").inference(), .9);
            });
    f.pipeline.tick();
    f.run();
    verify(f.store).append(any());
    f.time.advance(300);
    f.change("B");
    f.pipeline.tick();
    f.run();
    verify(f.store, times(2)).append(any());
    verify(f.extractor, times(4)).extract(any(), any());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void monitorEvidenceAndInputAreSavedWithTheOriginalObservation() throws Exception {
    var f = new Fixture();
    f.pipeline.tick();
    f.run();
    var rows = org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);
    verify(f.store, atLeastOnce()).replace(rows.capture());
    var row = rows.getValue();
    assertEquals(
        List.of("primary"),
        row.observations().stream().map(ActivityRecord.Observation::monitor).toList());
    assertEquals(row.capturedAt(), row.observations().getFirst().capturedAt());
    assertNotNull(row.detection().evidence().input());
  }
}
