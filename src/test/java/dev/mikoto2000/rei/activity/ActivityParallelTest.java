package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 作業状況並列の結果と状態の契約を検証するテスト。 */
class ActivityParallelTest {
  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void blockedBackgroundStillAllowsOsRecordingAndQueuesLatestForeground() throws Exception {
    var f = new DesktopContextQueueTest.Fixture();
    var active = new java.util.concurrent.atomic.AtomicInteger();
    when(f.extractor.extract(any(), any()))
        .thenAnswer(
            i -> {
              assertEquals(1, active.incrementAndGet());
              try {
                if ("background".equals(org.slf4j.MDC.get("activityScope"))) {
                  f.time.advance(30);
                  f.change("new");
                  f.pipeline.tick();
                  assertEquals(1, f.pipeline.queueMetrics().pending());
                  verify(f.store, times(2)).append(any());
                  throw new java.io.IOException();
                }
                return new ActivityExtractor.Result(
                    ActivityPolicyTest.record("2026-09-22T10:00:00Z", "social").inference(), .9);
              } finally {
                active.decrementAndGet();
              }
            });
    f.pipeline.tick();
    f.run();
    assertEquals(2, f.pipeline.queueMetrics().completed());
  }

  /** 元の観測内容を維持することを確認する。 */
  @Test
  void backgroundHasOneLatestSlotAndLateResultKeepsOriginalObservation() throws Exception {
    var f = new DesktopContextQueueTest.Fixture();
    f.pipeline.tick();
    f.time.advance(300);
    f.change("B");
    f.pipeline.tick();
    assertEquals(1, f.tasks.size());
    assertEquals(1, f.pipeline.desktopPending());
    f.run();
    var records = org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);
    verify(f.store, times(2)).append(records.capture());
    verify(f.store)
        .replace(
            argThat(
                r ->
                    r.id().equals(records.getAllValues().getLast().id())
                        && r.detection().visionDiagnostics().background().state()
                            == VisionDiagnostics.State.USED));
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void pauseDiscardsQueuedBackgroundAndDoesNotPublishAfterResume() throws Exception {
    var f = new DesktopContextQueueTest.Fixture();
    f.pipeline.tick();
    f.pipeline.pause();
    f.pipeline.resume();
    f.run();
    verifyNoInteractions(f.extractor);
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void desktopNeverExecutesBeforeForeground() throws Exception {
    var f = new DesktopContextQueueTest.Fixture();
    var scopes = new ArrayList<String>();
    when(f.extractor.extract(any(), any()))
        .thenAnswer(
            i -> {
              scopes.add(org.slf4j.MDC.get("activityScope"));
              return new ActivityExtractor.Result(
                  ActivityPolicyTest.record("2026-09-22T10:00:00Z", "social").inference(), .9);
            });
    f.pipeline.tick();
    f.run();
    assertEquals(List.of("foreground", "background"), scopes);
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void pauseOrCloseDuringBackgroundCallDiscardsResult(boolean close) throws Exception {
    var f = new DesktopContextQueueTest.Fixture();
    when(f.extractor.extract(any(), any()))
        .thenAnswer(
            i -> {
              if ("background".equals(org.slf4j.MDC.get("activityScope"))) {
                if (close) f.pipeline.close();
                else {
                  f.pipeline.pause();
                  f.pipeline.resume();
                }
              }
              return new ActivityExtractor.Result(
                  ActivityPolicyTest.record("2026-09-22T10:00:00Z", "social").inference(), .9);
            });
    f.pipeline.tick();
    f.run();
    verify(f.store, never())
        .replace(
            argThat(
                r ->
                    r.detection().visionDiagnostics().background().state()
                        == VisionDiagnostics.State.USED));
  }
}
