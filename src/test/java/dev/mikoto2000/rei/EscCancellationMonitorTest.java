package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Escキャンセル排他制御の結果と状態の契約を検証するテスト。 */
class EscCancellationMonitorTest {

  /** れいの起動の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void runCancelsWhenEscapeIsPressed() throws Exception {
    EscCancellationMonitor monitor = new EscCancellationMonitor();
    AtomicInteger cancelCount = new AtomicInteger();
    CompletableFuture<Integer> future = new CompletableFuture<>();

    Thread worker =
        new Thread(
            () -> {
              try {
                while (true) {
                  Thread.sleep(1000);
                }
              } catch (InterruptedException e) {
                future.complete(130);
              }
            });
    worker.start();

    int exitCode =
        monitor.await(
            future,
            timeoutMillis -> 27,
            () -> {
              cancelCount.incrementAndGet();
              worker.interrupt();
            });

    assertEquals(130, exitCode);
    assertEquals(1, cancelCount.get());
  }

  @Test
  void failedCommandPreservesFatalAndTerminalControlSignals() {
    var monitor = new EscCancellationMonitor();
    var fatal = new AssertionError("command failed");
    var interrupted = new org.jline.reader.UserInterruptException("partial input");

    assertSame(
        fatal,
        assertThrows(
            AssertionError.class,
            () -> monitor.await(CompletableFuture.failedFuture(fatal), timeout -> -1, () -> {})));
    assertSame(
        interrupted,
        assertThrows(
            org.jline.reader.UserInterruptException.class,
            () ->
                monitor.await(
                    CompletableFuture.failedFuture(interrupted), timeout -> -1, () -> {})));
  }

  @Test
  void failedCommandReportsAnExecutionFailureWithItsOriginalCause() {
    var monitor = new EscCancellationMonitor();
    var cause = new UnsupportedOperationException("command unavailable");
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> monitor.await(CompletableFuture.failedFuture(cause), timeout -> -1, () -> {}));
    assertSame(cause, failure.getCause());
  }
}
