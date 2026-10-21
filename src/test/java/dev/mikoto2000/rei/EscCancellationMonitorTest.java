package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
