package dev.mikoto2000.rei.bluesky;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlueskyReplySchedulerTest {

  @Mock
  private BlueskyReplyService service;

  @Test
  void delegatesToService() {
    BlueskyReplyScheduler scheduler = new BlueskyReplyScheduler(service);

    scheduler.run();

    verify(service).runOnce();
  }

  @Test
  void skipsWhenPreviousExecutionIsStillRunning() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(invocation -> {
      entered.countDown();
      assertTrue(release.await(1, TimeUnit.SECONDS));
      return null;
    }).when(service).runOnce();
    BlueskyReplyScheduler scheduler = new BlueskyReplyScheduler(service);
    ExecutorService executor = Executors.newSingleThreadExecutor();

    try {
      CompletableFuture<Void> firstRun = CompletableFuture.runAsync(scheduler::run, executor);
      assertTrue(entered.await(1, TimeUnit.SECONDS));

      scheduler.run();

      release.countDown();
      firstRun.get(1, TimeUnit.SECONDS);
      verify(service, times(1)).runOnce();
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void logsAndStopsSchedulerFailure() {
    doThrow(new IllegalStateException("boom")).when(service).runOnce();
    BlueskyReplyScheduler scheduler = new BlueskyReplyScheduler(service);

    assertDoesNotThrow(scheduler::run);

    verify(service).runOnce();
  }
}
