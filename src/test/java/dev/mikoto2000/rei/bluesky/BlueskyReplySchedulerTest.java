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

/** Bluesky返信実行予定の管理の結果と状態の契約を検証するテスト。 */
@ExtendWith(MockitoExtension.class)
class BlueskyReplySchedulerTest {

  /** 検証に使うサービス。 */
  @Mock private BlueskyReplyService service;

  /** Blueskyとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void delegatesToService() {
    BlueskyReplyScheduler scheduler = new BlueskyReplyScheduler(service);

    scheduler.run();

    verify(service).runOnce();
  }

  /** Blueskyとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void skipsWhenPreviousExecutionIsStillRunning() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              entered.countDown();
              assertTrue(release.await(1, TimeUnit.SECONDS));
              return null;
            })
        .when(service)
        .runOnce();
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

  /** ログ一覧およびについて、実行予定の管理失敗情報を停止することを確認する。 */
  @Test
  void logsAndStopsSchedulerFailure() {
    doThrow(new IllegalStateException("boom")).when(service).runOnce();
    BlueskyReplyScheduler scheduler = new BlueskyReplyScheduler(service);

    assertDoesNotThrow(scheduler::run);

    verify(service).runOnce();
  }
}
