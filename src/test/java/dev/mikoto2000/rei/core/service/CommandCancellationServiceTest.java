package dev.mikoto2000.rei.core.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import reactor.core.Disposable;

class CommandCancellationServiceTest {

  @Test
  void cancelDisposesSubscriptionAndInterruptsExecutionThread() throws Exception {
    CommandCancellationService service = new CommandCancellationService();
    CountDownLatch interrupted = new CountDownLatch(1);
    CountDownLatch disposed = new CountDownLatch(1);

    Thread executionThread = new Thread(() -> {
      service.begin(Thread.currentThread());
      try {
        while (true) {
          Thread.sleep(1000);
        }
      } catch (InterruptedException e) {
        interrupted.countDown();
      }
    });
    executionThread.start();

    service.register(new Disposable() {
      @Override
      public void dispose() {
        disposed.countDown();
      }

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

  @Test
  void clearResetsCancellationState() {
    CommandCancellationService service = new CommandCancellationService();

    service.begin(Thread.currentThread());
    service.cancel();
    service.clear();

    assertFalse(service.isCancellationRequested());
  }
}
