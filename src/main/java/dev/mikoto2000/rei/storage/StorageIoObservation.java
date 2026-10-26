package dev.mikoto2000.rei.storage;

import java.util.concurrent.atomic.LongAdder;

/**
 * データ保存の共通処理に必要な保存Io観測内容を扱う。
 *
 * <p>Optional in-memory application I/O probe. These counters are not OS fsync counters.
 */
final class StorageIoObservation {
  /** 現在。 */
  private static final ThreadLocal<StorageIoObservation> CURRENT = new ThreadLocal<>();

  /** forces／writes／バイト数。 */
  final LongAdder forces = new LongAdder(), writes = new LongAdder(), bytes = new LongAdder();

  /** 保存workerへ同じ計測先を引き継ぐ。 */
  static StorageIoObservation current() {
    return CURRENT.get();
  }

  /** データ保存の共通処理に必要な対象範囲を扱う。 */
  interface Scope extends AutoCloseable {
    /** データ保存の共通処理で利用した資源や登録を解放し、利用を終了する。 */
    @Override
    void close();
  }

  /** 状況を判断する根拠を得るため、保存Io観測内容を観測する。 */
  Scope observe() {
    var previous = CURRENT.get();
    CURRENT.set(this);
    return () -> {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    };
  }

  /** データ保存の共通処理に関するforcedを処理する。 */
  static void forced() {
    var probe = CURRENT.get();
    if (probe != null) probe.forces.increment();
  }

  /** データ保存の共通処理に関するwrittenを処理する。 */
  static void written(int bytes) {
    var probe = CURRENT.get();
    if (probe != null) {
      probe.writes.increment();
      probe.bytes.add(bytes);
    }
  }
}
