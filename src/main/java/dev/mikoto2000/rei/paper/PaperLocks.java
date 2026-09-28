package dev.mikoto2000.rei.paper;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Bounded, reentrant local locks serialize content generation and deletion of the same paper. */
final class PaperLocks {
  private static final ReentrantLock[] LOCKS =
      java.util.stream.IntStream.range(0, 64)
          .mapToObj(i -> new ReentrantLock())
          .toArray(ReentrantLock[]::new);

  static <T> T with(String id, PaperOperation op, Supplier<T> action) {
    var lock = LOCKS[Math.floorMod(id.hashCode(), LOCKS.length)];
    try {
      while (!lock.tryLock(100, TimeUnit.MILLISECONDS)) op.check();
      try {
        op.check();
        return action.get();
      } finally {
        lock.unlock();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new java.util.concurrent.CancellationException();
    }
  }
}
