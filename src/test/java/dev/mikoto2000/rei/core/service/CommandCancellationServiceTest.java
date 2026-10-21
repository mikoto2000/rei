package dev.mikoto2000.rei.core.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

/** コマンドキャンセルサービスの結果と状態の契約を検証するテスト。 */
class CommandCancellationServiceTest {

  /** 対話から使う共通操作の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancelDisposesSubscriptionAndInterruptsExecutionThread() throws Exception {
    CommandCancellationService service = new CommandCancellationService();
    CountDownLatch interrupted = new CountDownLatch(1);
    CountDownLatch disposed = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(1);

    Thread executionThread =
        new Thread(
            () -> {
              service.begin(Thread.currentThread());
              started.countDown();
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException e) {
                interrupted.countDown();
              }
            });
    executionThread.start();
    assertTrue(started.await(5, TimeUnit.SECONDS));

    service.register(
        new Disposable() {
          /** {@code dispose} の条件を再現するため、検証用の操作や状態を扱う。 */
          @Override
          public void dispose() {
            disposed.countDown();
          }

          /** 検証用に、対話から使う共通操作において、解放済みかどうかを判定する。 */
          @Override
          public boolean isDisposed() {
            return disposed.getCount() == 0;
          }
        });

    assertTrue(service.cancel());
    assertTrue(disposed.await(1, TimeUnit.SECONDS));
    assertTrue(interrupted.await(1, TimeUnit.SECONDS));
    assertTrue(service.isCancellationRequested());

    executionThread.join(1000);
  }

  /** 対話から使う共通操作の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void clearResetsCancellationState() {
    CommandCancellationService service = new CommandCancellationService();

    service.begin(Thread.currentThread());
    service.cancel();
    service.clear();

    assertFalse(service.isCancellationRequested());
  }
}
