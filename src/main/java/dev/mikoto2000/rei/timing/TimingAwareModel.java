package dev.mikoto2000.rei.timing;

/**
 * 実行時間の把握に必要な時間計測Awareモデルを扱う。
 *
 * <p>Optional observation adapter exposed by the existing model wrapper.
 */
public interface TimingAwareModel {
  /** 実行時間の把握で用いる時間計測を返す。 */
  TimingExecution timing();
}
