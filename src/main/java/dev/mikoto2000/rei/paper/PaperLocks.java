package dev.mikoto2000.rei.paper;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 論文の調査と整理に必要な論文排他制御一覧を扱う。
 *
 * <p>Bounded, reentrant local locks serialize content generation and deletion of the same paper.
 */
final class PaperLocks {
  /** 排他制御一覧。 */
  private static final ReentrantLock[] LOCKS =
      java.util.stream.IntStream.range(0, 64)
          .mapToObj(i -> new ReentrantLock())
          .toArray(ReentrantLock[]::new);

  /** 指定されたを反映した情報を作成する。 */
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
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw new java.util.concurrent.CancellationException();
    }
  }
}
