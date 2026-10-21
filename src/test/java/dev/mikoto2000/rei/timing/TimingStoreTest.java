package dev.mikoto2000.rei.timing;

import static dev.mikoto2000.rei.timing.TimingRecorder.Category;
import static dev.mikoto2000.rei.timing.TimingRecorder.Metric;
import static dev.mikoto2000.rei.timing.TimingRecorder.Run;
import static dev.mikoto2000.rei.timing.TimingRecorder.Span;
import static dev.mikoto2000.rei.timing.TimingRecorder.Status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 時間計測保存先の結果と状態の契約を検証するテスト。 */
class TimingStoreTest {
  /** 検証に使うナノ秒数。 */
  final AtomicLong nanos = new AtomicLong();

  /** 検証で使用する保存先を、指定された条件に合わせて用意する。 */
  TimingStore store(boolean enabled, int runs, int perRun, int total) {
    return new TimingStore(
        enabled,
        runs,
        perRun,
        total,
        Duration.ofHours(1),
        Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC),
        nanos::get);
  }

  /** 検証で使用する保存先を、指定された条件に合わせて用意する。 */
  TimingStore store() {
    return store(true, 100, 2000, 10000);
  }

  /** 検証で使用する状態の記録を、指定された条件に合わせて用意する。 */
  Run snapshot(TimingStore store, String id) {
    return store.snapshot("project", "session", id).orElseThrow();
  }

  /** 検証用に、要求された処理を開始する。 */
  void start(TimingStore store) {
    assertTrue(store.beginRun("run", "project", "session"));
  }

  /** {@code span} の条件を再現するため、検証用の操作や状態を扱う。 */
  void span(TimingStore store, String id, String parent, Category category) {
    assertTrue(store.beginSpan("run", id, parent, "request", "attempt", category));
  }

  /** 実行時間の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void serialIntervalsSeparateOccupancyWorkAndUnmeasuredTime() {
    var store = store();
    start(store);
    span(store, "tool", "run", Category.TOOL);
    nanos.set(3);
    store.endSpan("run", "tool", Status.SUCCESS);
    span(store, "llm", "run", Category.LLM);
    nanos.set(8);
    store.endSpan("run", "llm", Status.SUCCESS);
    nanos.set(10);
    store.finishRun("run", Status.SUCCESS);
    var run = snapshot(store, "run");
    assertEquals(10, run.elapsedNanos());
    assertFalse(run.incomplete());
    assertEquals(8, run.summary().workNanos());
    assertEquals(8, run.summary().occupiedNanos());
    assertEquals(2, run.summary().unmeasuredNanos());
    assertEquals(0, run.summary().overlapNanos());
  }

  /** 実行時間の把握で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void parallelAndThreeWayOverlapUseUnionRatherThanAddingBreakdown() {
    var store = store();
    start(store);
    span(store, "tool", "run", Category.TOOL);
    nanos.set(2);
    span(store, "search", "run", Category.SEARCH);
    nanos.set(3);
    span(store, "llm", "run", Category.LLM);
    nanos.set(5);
    store.endSpan("run", "llm", Status.SUCCESS);
    nanos.set(6);
    store.endSpan("run", "tool", Status.SUCCESS);
    nanos.set(8);
    store.endSpan("run", "search", Status.SUCCESS);
    nanos.set(10);
    store.finishRun("run", Status.SUCCESS);
    var summary = snapshot(store, "run").summary();
    assertEquals(14, summary.workNanos());
    assertEquals(8, summary.occupiedNanos());
    assertEquals(4, summary.overlapNanos());
    assertEquals(4, summary.crossCategoryOverlapNanos());
    assertEquals(6, summary.occupancyNanos().get(Category.TOOL));
    assertEquals(6, summary.occupancyNanos().get(Category.SEARCH));
    assertEquals(2, summary.unmeasuredNanos());
  }

  /** 実行時間の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void nestedAndSameCategorySpansDoNotDoubleCountOccupancy() {
    var store = store();
    start(store);
    span(store, "outer", "run", Category.TOOL);
    nanos.set(2);
    span(store, "inner", "outer", Category.TOOL);
    nanos.set(4);
    store.endSpan("run", "inner", Status.SUCCESS);
    nanos.set(8);
    store.endSpan("run", "outer", Status.SUCCESS);
    nanos.set(10);
    store.finishRun("run", Status.SUCCESS);
    var run = snapshot(store, "run");
    assertEquals("outer", run.spans().get(1).parentId());
    assertEquals(10, run.summary().workNanos());
    assertEquals(8, run.summary().occupancyNanos().get(Category.TOOL));
    assertEquals(2, run.summary().overlapNanos());
    assertEquals(0, run.summary().crossCategoryOverlapNanos());
  }

  /** 実行時間の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void missingTerminalIsIncompleteAndNeverInferredSuccessful() {
    var store = store();
    start(store);
    span(store, "open", "run", Category.LLM);
    nanos.set(9);
    var active = snapshot(store, "run");
    assertEquals(Status.INCOMPLETE, active.status());
    assertTrue(active.incomplete());
    store.finishRun("run", Status.SUCCESS);
    var finished = snapshot(store, "run");
    assertEquals(Status.INCOMPLETE, finished.spans().getFirst().status());
    assertTrue(finished.incomplete());
    nanos.set(20);
    assertEquals(9, snapshot(store, "run").elapsedNanos());
  }

  /** 実行時間の把握の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void failuresCancellationTimeoutAndDisconnectionRetainObservedStatus() {
    for (var status :
        List.of(Status.FAILED, Status.CANCELLED, Status.TIMED_OUT, Status.DISCONNECTED)) {
      var store = store();
      start(store);
      span(store, "attempt", "run", Category.LLM);
      nanos.incrementAndGet();
      store.endSpan("run", "attempt", status);
      store.finishRun("run", status);
      assertEquals(status, snapshot(store, "run").status());
      assertEquals(status, snapshot(store, "run").spans().getFirst().status());
    }
  }

  /** 実行時間の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void retryRequestAttemptsAndParentsRemainInOriginalRun() {
    var store = store();
    start(store);
    assertTrue(store.beginSpan("run", "request", "run", "request", null, Category.OTHER));
    assertTrue(store.beginSpan("run", "attempt1", "request", "request", "1", Category.LLM));
    nanos.set(2);
    store.endSpan("run", "attempt1", Status.FAILED);
    assertTrue(store.beginSpan("run", "backoff", "request", "request", "2", Category.RETRY));
    nanos.set(3);
    store.endSpan("run", "backoff", Status.SUCCESS);
    assertTrue(store.beginSpan("run", "attempt2", "request", "request", "2", Category.LLM));
    nanos.set(6);
    store.endSpan("run", "attempt2", Status.SUCCESS);
    store.endSpan("run", "request", Status.SUCCESS);
    store.finishRun("run", Status.SUCCESS);
    var run = snapshot(store, "run");
    assertEquals(4, run.spans().size());
    assertEquals("request", run.spans().getLast().parentId());
    assertEquals("2", run.spans().getLast().attemptId());
    assertEquals(1, store.statistics().runs());
  }

  /** 実行時間の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void runAndTotalCapsEvictOldestAndLateEventsDoNotResurrectThem() {
    var store = store(true, 2, 2, 3);
    start(store);
    span(store, "one", "run", Category.TOOL);
    store.beginRun("b", "project", "session");
    store.beginSpan("b", "b1", "b", null, null, Category.TOOL);
    store.beginRun("c", "project", "session");
    store.beginSpan("c", "c1", "c", null, null, Category.TOOL);
    assertTrue(store.snapshot("project", "session", "run").isEmpty());
    assertFalse(store.endSpan("run", "one", Status.SUCCESS));
    assertFalse(store.beginSpan("run", "late", "run", null, null, Category.TOOL));
    assertEquals(2, store.statistics().runs());
    assertTrue(store.statistics().spans() <= 3);
    assertTrue(store.statistics().evictedRuns() > 0);
  }

  /** 実行時間の把握で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void perRunLimitDropsRecordWithoutFailingTheRun() {
    var store = store(true, 2, 1, 2);
    start(store);
    span(store, "one", "run", Category.TOOL);
    assertFalse(store.beginSpan("run", "two", "run", null, null, Category.SEARCH));
    nanos.set(4);
    store.endSpan("run", "one", Status.SUCCESS);
    assertTrue(store.finishRun("run", Status.SUCCESS));
    assertEquals(1, snapshot(store, "run").spans().size());
    assertTrue(snapshot(store, "run").incomplete());
    assertTrue(store.statistics().droppedSpans() > 0);
  }

  /** 実行時間の把握で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void totalSpanLimitEvictsAnOlderRunWithoutExceedingMemoryCap() {
    var store = store(true, 5, 2, 2);
    start(store);
    span(store, "one", "run", Category.TOOL);
    span(store, "two", "run", Category.TOOL);
    store.beginRun("new", "project", "session");
    assertTrue(store.beginSpan("new", "new1", "new", null, null, Category.LLM));
    assertTrue(store.snapshot("project", "session", "run").isEmpty());
    assertTrue(store.statistics().spans() <= 2);
  }

  /** 有効期間および無効状態集約対象について、なし上限のない状態を保持することを確認する。 */
  @Test
  void ttlAndDisabledCollectionRetainNoUnboundedState() {
    var store = store();
    start(store);
    nanos.set(Duration.ofHours(2).toNanos());
    assertTrue(store.snapshot("project", "session", "run").isEmpty());
    var disabled = store(false, 2, 2, 2);
    assertFalse(disabled.beginRun("disabled", "project", "session"));
    assertFalse(disabled.beginSpan("disabled", "span", null, null, null, Category.TOOL));
    assertEquals(0, disabled.statistics().runs());
    assertEquals(0, disabled.statistics().spans());
  }

  /** 実行時間の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void metadataHasNoFreeFormContentAndUsageDoesNotInventUnknownMetrics() {
    var store = store();
    start(store);
    span(store, "llm", "run", Category.LLM);
    nanos.set(2);
    store.markSpan("run", "llm", Metric.FIRST_GENERATED_TOKEN);
    nanos.set(3);
    store.markSpan("run", "llm", Metric.FIRST_GENERATED_TOKEN);
    store.usage("run", "llm", 12L, 8L, null, null);
    nanos.set(10);
    store.endSpan("run", "llm", Status.SUCCESS);
    store.finishRun("run", Status.SUCCESS);
    var span = snapshot(store, "run").spans().getFirst();
    assertEquals(2, span.milestones().get(Metric.FIRST_GENERATED_TOKEN));
    assertNull(span.generationTps());
    assertFalse(span.milestones().containsKey(Metric.FIRST_COMMUNICATION));
    assertEquals(8, span.outputTokens());
    assertTrue(
        Arrays.stream(Span.class.getRecordComponents())
            .noneMatch(
                c ->
                    Set.of(
                            "prompt",
                            "response",
                            "arguments",
                            "content",
                            "body",
                            "audio",
                            "metadata")
                        .contains(c.getName())));
  }

  /** 実行時間の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void tpsRequiresRealCountAndMatchingGenerationDuration() {
    var store = store();
    start(store);
    span(store, "llm", "run", Category.LLM);
    store.usage("run", "llm", 12L, 8L, 6L, 2_000_000_000L);
    nanos.set(3_000_000_000L);
    store.endSpan("run", "llm", Status.SUCCESS);
    store.finishRun("run", Status.SUCCESS);
    assertEquals(3, snapshot(store, "run").spans().getFirst().generationTps());
  }

  /** 実行時間の把握で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void sessionAndProjectHistoryAreIsolatedAndDuplicateTerminalsDoNotChangeDurations() {
    var store = store();
    start(store);
    span(store, "one", "run", Category.TOOL);
    nanos.set(2);
    store.endSpan("run", "one", Status.FAILED);
    store.finishRun("run", Status.FAILED);
    nanos.set(10);
    assertFalse(store.endSpan("run", "one", Status.SUCCESS));
    assertFalse(store.finishRun("run", Status.SUCCESS));
    assertEquals(2, snapshot(store, "run").elapsedNanos());
    assertTrue(store.snapshot("other", "session", "run").isEmpty());
    assertTrue(store.latest("project", "other").isEmpty());
    assertEquals("run", store.latest("project", "session").orElseThrow().id());
  }

  /** 実行時間の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void wallClockChangesDoNotChangeMonotonicDurations() {
    var wall =
        new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-10T00:00:00Z"));
    var clock =
        new Clock() {
          /** 検証用に、実行時間の把握で参照するタイムゾーンを返す。 */
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          /** 検証用に、指定されたタイムゾーンを反映した情報を作成する。 */
          public Clock withZone(ZoneId zone) {
            return this;
          }

          /** {@code instant} の条件を再現するための補助情報を返す。 */
          public Instant instant() {
            return wall.get();
          }
        };
    var store = new TimingStore(true, 2, 2, 2, Duration.ofHours(1), clock, nanos::get);
    start(store);
    span(store, "one", "run", Category.TOOL);
    wall.set(wall.get().minus(Duration.ofDays(7)));
    nanos.set(5);
    store.endSpan("run", "one", Status.SUCCESS);
    store.finishRun("run", Status.SUCCESS);
    assertEquals(5, snapshot(store, "run").elapsedNanos());
    assertEquals(Instant.parse("2026-10-10T00:00:00Z"), snapshot(store, "run").startedAt());
  }
}
