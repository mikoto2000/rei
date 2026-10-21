package dev.mikoto2000.rei.http;

import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 外部情報の取得に必要なホスト受付を扱う。
 *
 * <p>Reference-counted host permits, removed when the last owner or waiter leaves.
 */
public final class HostAdmission {
  /** 外部情報の取得に必要な利用権を扱う。 */
  @FunctionalInterface
  public interface Lease extends AutoCloseable {
    /** 外部情報の取得で利用した資源や登録を解放し、利用を終了する。 */
    @Override
    void close();
  }

  /** 該当なし。 */
  public static final Lease NONE = () -> {};

  /** 外部情報の取得に必要な項目を扱う。 */
  private static final class Entry {
    /** 同時利用できる枠の管理。 */
    final Semaphore slots;

    /** 利用者一覧。 */
    int users;

    /** 指定された値や利用先を使って、Entryを初期化する。 */
    Entry(int maximum) {
      slots = new Semaphore(maximum, true);
    }
  }

  /** 最大。 */
  private final int maximum;

  /** 項目一覧。 */
  private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

  /** 指定された値や利用先を使って、HostAdmissionを初期化する。 */
  public HostAdmission(int maximum) {
    if (maximum < 1 || maximum > 3)
      throw new IllegalArgumentException("Invalid per-host admission limit");
    this.maximum = maximum;
  }

  /** 外部情報の取得で用いる確保を返す。 */
  public Lease acquire(String host, FetchOperation operation) {
    String key = host.toLowerCase(Locale.ROOT);
    Entry entry =
        entries.compute(
            key,
            (ignored, existing) -> {
              var value = existing == null ? new Entry(maximum) : existing;
              value.users++;
              return value;
            });
    boolean acquired = false;
    try {
      while (!acquired) {
        operation.check();
        acquired = entry.slots.tryAcquire(50, TimeUnit.MILLISECONDS);
      }
      operation.check();
      var released = new AtomicBoolean();
      return () -> {
        // 複数の経路から同じ処理が求められても、状態の切り替えは一度だけ行う。
        if (released.compareAndSet(false, true)) {
          entry.slots.release();
          release(key, entry);
        }
      };
    } catch (InterruptedException interrupted) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      if (acquired) entry.slots.release();
      release(key, entry);
      throw new CancellationException();
    } catch (RuntimeException stopped) {
      if (acquired) entry.slots.release();
      release(key, entry);
      throw stopped;
    }
  }

  /** 後続の処理が利用できるよう、ホスト受付を解放する。 */
  private void release(String key, Entry expected) {
    entries.compute(
        key, (ignored, current) -> current == expected && --current.users == 0 ? null : current);
  }

  /** 外部情報の取得で用いる項目一覧を返す。 */
  public int entries() {
    return entries.size();
  }
}
