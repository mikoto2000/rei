package dev.mikoto2000.rei.timing;

import static dev.mikoto2000.rei.timing.TimingRecorder.Category;
import static dev.mikoto2000.rei.timing.TimingRecorder.Span;
import static dev.mikoto2000.rei.timing.TimingRecorder.Status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 時間計測同時実行数の結果と状態の契約を検証するテスト。 */
class TimingConcurrencyTest {
  /** 並行試行回数について、識別子一覧を維持することを確認する。 */
  @Test
  void concurrentAttemptsKeepTheirIdsAndCountersIsolated() throws Exception {
    var tick = new AtomicLong();
    var store =
        new TimingStore(
            true, 2, 500, 1000, Duration.ofHours(1), Clock.systemUTC(), tick::incrementAndGet);
    assertTrue(store.beginRun("run", "project", "session"));
    try (var pool = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<Callable<Boolean>>();
      for (int index = 0; index < 400; index++) {
        String id = "span-" + index;
        tasks.add(
            () ->
                store.beginSpan("run", id, "run", "request", id, Category.LLM)
                    && store.endSpan("run", id, Status.SUCCESS));
      }
      for (var result : pool.invokeAll(tasks)) assertTrue(result.get());
    }
    store.finishRun("run", Status.SUCCESS);
    var run = store.snapshot("project", "session", "run").orElseThrow();
    assertEquals(400, run.spans().size());
    assertEquals(400, run.spans().stream().map(Span::id).distinct().count());
    assertFalse(run.incomplete());
    assertTrue(run.summary().occupiedNanos() <= run.elapsedNanos());
    assertTrue(run.summary().workNanos() >= run.summary().occupiedNanos());
    assertEquals(400, store.statistics().spans());
  }

  /** 実行時間の把握で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void disabledEnvironmentDoesNotParseUnusedLimits() {
    var env =
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("rei.timing.enabled", "false")
            .withProperty("rei.timing.max-runs", "invalid-private-value");
    var store = new TimingStore(env, Clock.systemUTC());
    assertFalse(store.enabled());
    assertEquals(0, store.statistics().runs());
  }
}
