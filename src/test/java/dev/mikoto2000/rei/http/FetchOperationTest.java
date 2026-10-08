package dev.mikoto2000.rei.http;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class FetchOperationTest {
  @Test void deadlineCancelsThePendingFuture() {
    var future = new CompletableFuture<String>();
    var operation = FetchOperation.active().withTimeout(Duration.ofMillis(20));
    assertEquals(HttpFetchException.Code.TOTAL_TIMEOUT,
        assertThrows(HttpFetchException.class, () -> operation.await(future)).code());
    assertTrue(future.isCancelled());
  }
  @Test void cancellationDoesNotStartOrLeavePendingWork() {
    var operation = new FetchOperation(() -> { throw new CancellationException(); }, Long.MAX_VALUE);
    var future = new CompletableFuture<String>();
    assertThrows(CancellationException.class, () -> operation.await(future));
    assertTrue(future.isCancelled());
  }
  @Test void interruptedWaitPreservesInterruptAndCancelsFuture() {
    var future = new CompletableFuture<String>();
    Thread.currentThread().interrupt();
    try {
      assertThrows(CancellationException.class, () -> FetchOperation.active().await(future));
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(future.isCancelled());
    } finally { Thread.interrupted(); }
  }
  @Test void completedFutureIsReturnedAndReadAndConnectTimeoutsRemainDistinct() {
    assertEquals("ok", FetchOperation.active().await(CompletableFuture.completedFuture("ok")));
    assertEquals(HttpFetchException.Code.CONNECT_TIMEOUT,
        assertThrows(HttpFetchException.class, () -> FetchOperation.active().await(
            CompletableFuture.failedFuture(new io.netty.channel.ConnectTimeoutException("fixture")))).code());
    assertEquals(HttpFetchException.Code.READ_TIMEOUT,
        assertThrows(HttpFetchException.class, () -> FetchOperation.active().await(
            CompletableFuture.failedFuture(io.netty.handler.timeout.ReadTimeoutException.INSTANCE))).code());
  }
}
