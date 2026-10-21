package dev.mikoto2000.rei.voice;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Objects;

/**
 * 音声による対話に必要な音声性能測定計測値を扱う。
 *
 * <p>Reproducible benchmark statistics; punctuation/spacing normalization does not rewrite words.
 */
public final class VoiceBenchmarkMetrics {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private VoiceBenchmarkMetrics() {}

  /** 同じ意味の指定を共通に扱えるよう、表記や形式をそろえる。 */
  public static String normalize(String text) {
    return Normalizer.normalize(Objects.requireNonNull(text), Normalizer.Form.NFKC)
        .replaceAll("[\\p{P}\\p{Z}\\s]", "");
  }

  /** 音声による対話で用いる文字エラー一覧を返す。 */
  public static int characterErrors(String reference, String hypothesis) {
    int[] expected = normalize(reference).codePoints().toArray(),
        actual = normalize(hypothesis).codePoints().toArray();
    int[] previous = new int[actual.length + 1], current = new int[actual.length + 1];
    for (int j = 0; j <= actual.length; j++) previous[j] = j;
    for (int i = 1; i <= expected.length; i++) {
      current[0] = i;
      for (int j = 1; j <= actual.length; j++)
        current[j] =
            Math.min(
                Math.min(previous[j] + 1, current[j - 1] + 1),
                previous[j - 1] + (expected[i - 1] == actual[j - 1] ? 0 : 1));
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[actual.length];
  }

  /** 音声による対話で用いるcerを返す。 */
  public static double cer(String reference, String hypothesis) {
    int count = normalize(reference).codePointCount(0, normalize(reference).length());
    if (count == 0)
      throw new IllegalArgumentException(
          "CER needs a nonempty reference; score silence by false sends");
    return (double) characterErrors(reference, hypothesis) / count;
  }

  /** 音声による対話で用いるpercentileを返す。 */
  public static double percentile(double[] measurements, double quantile) {
    if (measurements == null
        || measurements.length == 0
        || !Double.isFinite(quantile)
        || quantile <= 0
        || quantile > 1) throw new IllegalArgumentException("Invalid percentile request");
    double[] sorted = measurements.clone();
    for (double value : sorted)
      if (!Double.isFinite(value) || value < 0)
        throw new IllegalArgumentException("Invalid measurement");
    Arrays.sort(sorted);
    return sorted[(int) Math.ceil(quantile * sorted.length) - 1];
  }
}
