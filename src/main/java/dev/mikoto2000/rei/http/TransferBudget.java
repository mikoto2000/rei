package dev.mikoto2000.rei.http;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 引き継ぎの使用量と上限を管理する。
 *
 * <p>Payload volume across a bounded operation; cached decoded bodies also consume the logical
 * volume.
 */
public final class TransferBudget {
  /** 最大機能の接続／最大復元済み。 */
  private final long maximumWire, maximumDecoded;

  /** 機能の接続／復元済み。 */
  private final AtomicLong wire = new AtomicLong(), decoded = new AtomicLong();

  /** 識別情報。 */
  private final String identity = UUID.randomUUID().toString();

  /** 指定された値や利用先を使って、TransferBudgetを初期化する。 */
  public TransferBudget(long maximumWire, long maximumDecoded) {
    if (maximumWire < 1
        || maximumDecoded < 1
        || maximumWire > 512L * 1024 * 1024
        || maximumDecoded > 512L * 1024 * 1024)
      throw new IllegalArgumentException("Invalid aggregate transfer limits");
    this.maximumWire = maximumWire;
    this.maximumDecoded = maximumDecoded;
  }

  /** 外部情報の取得に関する機能の接続を処理する。 */
  public void wire(long bytes) {
    reserve(wire, bytes, maximumWire);
  }

  /** 外部情報の取得に関する復元済みを処理する。 */
  public void decoded(long bytes) {
    reserve(decoded, bytes, maximumDecoded);
  }

  /** 競合する処理と利用範囲を調整するため、引き継ぎ予算を確保する。 */
  private static void reserve(AtomicLong count, long incoming, long maximum) {
    if (incoming < 0) throw new IllegalArgumentException("Negative transfer size");
    for (; ; ) {
      long current = count.get();
      if (incoming > maximum - current)
        throw new HttpFetchException(HttpFetchException.Code.TRANSFER_BUDGET);
      if (count.compareAndSet(current, current + incoming)) return;
    }
  }

  /** 外部情報の取得で用いる機能の接続バイト数を返す。 */
  public long wireBytes() {
    return wire.get();
  }

  /** 外部情報の取得で用いる復元済みバイト数を返す。 */
  public long decodedBytes() {
    return decoded.get();
  }

  /** 外部情報の取得で用いる識別情報を返す。 */
  public String identity() {
    return identity;
  }
}
