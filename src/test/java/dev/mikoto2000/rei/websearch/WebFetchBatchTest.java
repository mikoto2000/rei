package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.http.*;

class WebFetchBatchTest {
  List<WebSearchSelection.Candidate> candidates(String... hosts) {
    var result = new ArrayList<WebSearchSelection.Candidate>();
    for (int i = 0; i < hosts.length; i++) result.add(new WebSearchSelection.Candidate(
        new WebSearchResult("page " + i, "https://" + hosts[i] + "/" + i, "snippet", null), List.of(), i));
    return result;
  }
  @Test void threeDelayedFetchesOverlapAndResultsRetainRankRatherThanCompletionOrder() throws Exception {
    try (var batch = new WebFetchBatch(3, 2, 32); var caller = Executors.newSingleThreadExecutor()) {
      var started = new CountDownLatch(3); var release = new CountDownLatch(1);
      var input = candidates("a.example", "b.example", "c.example");
      var future = caller.submit(() -> batch.fetch(input, Duration.ofSeconds(3), candidate -> {
        started.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS));
        Thread.sleep((2 - candidate.rank()) * 40L); return candidate.rank();
      }));
      assertTrue(started.await(2, TimeUnit.SECONDS), "All three must start before any may finish");
      release.countDown();
      assertEquals(List.of(0, 1, 2), future.get(2, TimeUnit.SECONDS).stream().map(WebFetchBatch.Outcome::value).toList());
      assertEquals(0, batch.activeTasks()); assertEquals(0, batch.hostEntries());
    }
  }
  @Test void globalAndHostLimitsApplyAcrossConcurrentBatches() throws Exception {
    try (var batch = new WebFetchBatch(3, 1, 32); var callers = Executors.newFixedThreadPool(2)) {
      var live = new AtomicInteger(); var max = new AtomicInteger();
      var hostLive = new ConcurrentHashMap<String, AtomicInteger>(); var hostMax = new AtomicInteger();
      WebFetchBatch.Fetcher<Integer> loader = candidate -> {
        String host = java.net.URI.create(candidate.result().url()).getHost();
        var perHost = hostLive.computeIfAbsent(host, key -> new AtomicInteger());
        max.accumulateAndGet(live.incrementAndGet(), Math::max);
        hostMax.accumulateAndGet(perHost.incrementAndGet(), Math::max);
        try { Thread.sleep(80); return candidate.rank(); }
        finally { live.decrementAndGet(); perHost.decrementAndGet(); }
      };
      var first = callers.submit(() -> batch.fetch(candidates("a.example", "a.example", "b.example"), Duration.ofSeconds(3), loader));
      var second = callers.submit(() -> batch.fetch(candidates("a.example", "c.example", "d.example"), Duration.ofSeconds(3), loader));
      assertTrue(first.get().stream().allMatch(WebFetchBatch.Outcome::success));
      assertTrue(second.get().stream().allMatch(WebFetchBatch.Outcome::success));
      assertTrue(max.get() <= 3); assertEquals(1, hostMax.get()); assertEquals(0, live.get());
      assertEquals(0, batch.hostEntries());
    }
  }
  @Test void failedPageDoesNotDiscardSuccessfulPage() {
    try (var batch = new WebFetchBatch(2, 2, 32)) {
      var results = batch.fetch(candidates("a.example", "b.example"), Duration.ofSeconds(2), candidate -> {
        if (candidate.rank() == 0) throw new java.io.IOException("private diagnostic"); return "evidence";
      });
      assertEquals(WebFetchBatch.Status.FAILED, results.getFirst().status());
      assertEquals("FETCH_ERROR", results.getFirst().errorType()); assertEquals("evidence", results.getLast().value());
    }
  }
  @Test void deadlineKeepsLaterCompletedSuccessAndStopsRunningAndQueuedTasks() throws Exception {
    try (var batch = new WebFetchBatch(2, 1, 32)) {
      var stopped = new CountDownLatch(1); var calls = new AtomicInteger();
      var results = batch.fetch(candidates("a.example", "b.example", "a.example"), Duration.ofMillis(250), candidate -> {
        calls.incrementAndGet(); if (candidate.rank() == 1) return "quick";
        try { Thread.sleep(5000); return "late"; } finally { stopped.countDown(); }
      });
      assertEquals(WebFetchBatch.Status.TIMEOUT, results.getFirst().status());
      assertEquals("quick", results.get(1).value()); assertEquals(WebFetchBatch.Status.TIMEOUT, results.get(2).status());
      assertTrue(stopped.await(1, TimeUnit.SECONDS)); assertEquals(2, calls.get());
      assertEquals(0, batch.activeTasks()); assertEquals(0, batch.queuedTasks()); assertEquals(0, batch.hostEntries());
    }
  }
  @Test void callerCancellationStopsQueuedWorkAndReleasesExecutorAdmission() throws Exception {
    try (var batch = new WebFetchBatch(1, 1, 32); var caller = Executors.newSingleThreadExecutor()) {
      var started = new CountDownLatch(1); var stop = new AtomicBoolean(); var calls = new AtomicInteger();
      var future = caller.submit(() -> {
        try (var scope = FetchScope.enter(new FetchOperation(() -> { if (stop.get()) throw new CancellationException(); }, Long.MAX_VALUE))) {
          return batch.fetch(candidates("a.example", "b.example"), Duration.ofSeconds(5), candidate -> {
            calls.incrementAndGet(); started.countDown(); Thread.sleep(5000); return 1;
          });
        }
      });
      assertTrue(started.await(2, TimeUnit.SECONDS)); stop.set(true);
      assertInstanceOf(CancellationException.class, assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS)).getCause());
      assertEquals(1, calls.get()); assertEquals(0, batch.activeTasks()); assertEquals(0, batch.hostEntries());
      assertTrue(batch.fetch(candidates("c.example"), Duration.ofSeconds(1), candidate -> 2).getFirst().success());
    }
  }
  @Test void saturatedQueueRejectsExplicitlyAndCloseReleasesAllWork() throws Exception {
    var batch = new WebFetchBatch(1, 1, 1);
    try (var caller = Executors.newSingleThreadExecutor()) {
      var started = new CountDownLatch(1);
      var future = caller.submit(() -> batch.fetch(candidates("a.example", "b.example", "c.example", "d.example"),
          Duration.ofSeconds(5), candidate -> { started.countDown(); Thread.sleep(5000); return 1; }));
      assertTrue(started.await(2, TimeUnit.SECONDS));
      // The bounded queue cannot admit all four candidates.
      Thread.sleep(100); batch.close();
      var results = future.get(2, TimeUnit.SECONDS);
      assertTrue(results.stream().anyMatch(result -> result.status() == WebFetchBatch.Status.REJECTED));
      assertTrue(batch.terminated()); assertEquals(0, batch.activeTasks()); assertEquals(0, batch.hostEntries());
      assertEquals(WebFetchBatch.Status.REJECTED, batch.fetch(candidates("e.example"), Duration.ofSeconds(1), candidate -> 1).getFirst().status());
    } finally { batch.close(); }
  }
  @Test void invalidConcurrencyAndDeadlineAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> new WebFetchBatch(4, 2, 32));
    assertThrows(IllegalArgumentException.class, () -> new WebFetchBatch(2, 3, 32));
    assertThrows(IllegalArgumentException.class, () -> new WebFetchBatch(2, 2, 0));
    try (var batch = new WebFetchBatch(2, 2, 32)) {
      assertThrows(IllegalArgumentException.class, () -> batch.fetch(candidates("a.example"), Duration.ZERO, candidate -> 1));
    }
  }
  @Test void invalidConfigurationCannotSilentlyCreateAnUnboundedPool() {
    var properties = new WebSearchProperties(); properties.setFetchParallelism(4);
    assertThrows(IllegalArgumentException.class, properties::validateSelection);
    assertThrows(IllegalArgumentException.class, () -> new WebFetchBatch(properties));
    properties.setFetchParallelism(3); properties.setFetchBatchTimeoutSeconds(301);
    assertThrows(IllegalArgumentException.class, properties::validateSelection);
    properties.setFetchBatchTimeoutSeconds(30); properties.setFetchQueueCapacity(65);
    assertThrows(IllegalArgumentException.class, properties::validateSelection);
  }
  @Test void inheritedDeadlineProducesPartialResultsRatherThanLosingSuccess() {
    try (var batch = new WebFetchBatch(2, 2, 32);
        var scope = FetchScope.enter(FetchOperation.active().withTimeout(Duration.ofMillis(150)))) {
      var results = batch.fetch(candidates("a.example", "b.example"), Duration.ofSeconds(5), candidate -> {
        if (candidate.rank() == 0) { Thread.sleep(5000); return "slow"; } return "quick";
      });
      assertEquals("quick", results.get(1).value()); assertEquals(WebFetchBatch.Status.TIMEOUT, results.getFirst().status());
    }
  }
  @Test void delayedFixtureReducesWallTimeWithEquivalentValues() {
    var input = candidates("a.example", "b.example", "c.example");
    WebFetchBatch.Fetcher<Integer> loader = candidate -> { Thread.sleep(120); return candidate.rank(); };
    long sequential;
    try (var batch = new WebFetchBatch(1, 1, 32)) {
      long started = System.nanoTime(); var results = batch.fetch(input, Duration.ofSeconds(5), loader);
      sequential = System.nanoTime() - started;
      assertEquals(List.of(0, 1, 2), results.stream().map(WebFetchBatch.Outcome::value).toList());
    }
    try (var batch = new WebFetchBatch(3, 2, 32)) {
      long started = System.nanoTime(); var results = batch.fetch(input, Duration.ofSeconds(5), loader);
      long parallel = System.nanoTime() - started;
      System.out.printf("WEB_FETCH_FIXTURE sequential_ms=%.1f parallel_ms=%.1f%n", sequential / 1_000_000.0, parallel / 1_000_000.0);
      assertEquals(List.of(0, 1, 2), results.stream().map(WebFetchBatch.Outcome::value).toList());
      assertTrue(parallel < sequential * 0.85, "Controlled 120ms fixture should overlap");
    }
  }
  @Test void runStopRaisedInAWorkerStopsTheWholeBatchPromptly() {
    try (var batch = new WebFetchBatch(2, 2, 32)) {
      var stopped = assertThrows(dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.class,
          () -> batch.fetch(candidates("a.example", "b.example"), Duration.ofSeconds(5), candidate -> {
            if (candidate.rank() == 0) throw new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(
                dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_BUDGET_EXCEEDED);
            Thread.sleep(5000); return 1;
          }));
      assertEquals(dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_BUDGET_EXCEEDED, stopped.reason());
      assertEquals(0, batch.activeTasks()); assertEquals(0, batch.hostEntries());
    }
  }
  @Test void repeatedCancelAndStartRacesLeaveNoHostReservations() throws Exception {
    try (var batch = new WebFetchBatch(3, 1, 32)) {
      for (int iteration = 0; iteration < 10; iteration++) {
        var result = batch.fetch(candidates("a.example", "a.example", "b.example"), Duration.ofMillis(20), candidate -> {
          Thread.sleep(200); return 1;
        });
        assertEquals(3, result.size()); assertEquals(0, batch.activeTasks()); assertEquals(0, batch.hostEntries());
      }
    }
  }
}
