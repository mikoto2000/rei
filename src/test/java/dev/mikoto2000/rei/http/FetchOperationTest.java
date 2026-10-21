package dev.mikoto2000.rei.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** 取得操作の結果と状態の契約を検証するテスト。 */
class FetchOperationTest {
  /** 外部情報の取得の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void deadlineCancelsThePendingFuture() {
    var future = new CompletableFuture<String>();
    var operation = FetchOperation.active().withTimeout(Duration.ofMillis(20));
    assertEquals(
        HttpFetchException.Code.TOTAL_TIMEOUT,
        assertThrows(HttpFetchException.class, () -> operation.await(future)).code());
    assertTrue(future.isCancelled());
  }

  /** 外部情報の取得の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationDoesNotStartOrLeavePendingWork() {
    var operation =
        new FetchOperation(
            () -> {
              throw new CancellationException();
            },
            Long.MAX_VALUE);
    var future = new CompletableFuture<String>();
    assertThrows(CancellationException.class, () -> operation.await(future));
    assertTrue(future.isCancelled());
  }

  /** 外部情報の取得の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void interruptedWaitPreservesInterruptAndCancelsFuture() {
    var future = new CompletableFuture<String>();
    Thread.currentThread().interrupt();
    try {
      assertThrows(CancellationException.class, () -> FetchOperation.active().await(future));
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(future.isCancelled());
    } finally {
      Thread.interrupted();
    }
  }

  /** 外部情報の取得で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void completedFutureIsReturnedAndReadAndConnectTimeoutsRemainDistinct() {
    assertEquals("ok", FetchOperation.active().await(CompletableFuture.completedFuture("ok")));
    assertEquals(
        HttpFetchException.Code.CONNECT_TIMEOUT,
        assertThrows(
                HttpFetchException.class,
                () ->
                    FetchOperation.active()
                        .await(
                            CompletableFuture.failedFuture(
                                new io.netty.channel.ConnectTimeoutException("fixture"))))
            .code());
    assertEquals(
        HttpFetchException.Code.READ_TIMEOUT,
        assertThrows(
                HttpFetchException.class,
                () ->
                    FetchOperation.active()
                        .await(
                            CompletableFuture.failedFuture(
                                io.netty.handler.timeout.ReadTimeoutException.INSTANCE)))
            .code());
  }
}
