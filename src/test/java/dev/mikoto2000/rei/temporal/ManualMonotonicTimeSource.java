package dev.mikoto2000.rei.temporal;

/** 検証条件を再現するためのManualMonotonic時刻取得元。 */
public class ManualMonotonicTimeSource implements MonotonicTimeSource {
  /** 検証に使うナノ秒数。 */
  private long nanos;

  /** 検証条件に必要な値を受け取り、ManualMonotonicTimeSourceを用意する。 */
  public ManualMonotonicTimeSource(long nanos) {
    this.nanos = nanos;
  }

  /** {@code nanoTime} の条件を再現するための補助情報を返す。 */
  @Override
  public long nanoTime() {
    return nanos;
  }

  /** {@code advanceNanos} の条件を再現するため、検証用の操作や状態を扱う。 */
  public void advanceNanos(long delta) {
    nanos += delta;
  }
}
