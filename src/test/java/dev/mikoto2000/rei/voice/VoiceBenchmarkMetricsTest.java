package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** 音声性能測定計測値の結果と状態の契約を検証するテスト。 */
class VoiceBenchmarkMetricsTest {
  /** 音声による対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void japaneseCerCountsCodePointsAndNormalizesPresentationOnly() {
    assertThat(VoiceBenchmarkMetrics.characterErrors("こんにちは。 音声入力", "こんにちは音声入カ")).isEqualTo(1);
    assertThat(VoiceBenchmarkMetrics.characterErrors("𠮷", "吉")).isEqualTo(1);
    assertThat(VoiceBenchmarkMetrics.characterErrors("ABC", "abc")).isEqualTo(3);
  }

  /** 音声による対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void insertionDeletionAndEmptyReferenceRemainVisible() {
    assertThat(VoiceBenchmarkMetrics.characterErrors("あいう", "あうえ")).isEqualTo(2);
    assertThat(VoiceBenchmarkMetrics.characterErrors("", "幻覚")).isEqualTo(2);
    assertThatThrownBy(() -> VoiceBenchmarkMetrics.cer("", "幻覚"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(VoiceBenchmarkMetrics.cer("あいう", "あう")).isCloseTo(1.0 / 3, within(0.00001));
  }

  /** 不正なを受け付けないことを確認する。 */
  @Test
  void percentileUsesNearestRankAndRejectsInvalidOrEmptyMeasurements() {
    assertThat(VoiceBenchmarkMetrics.percentile(new double[] {4, 1, 3, 2}, .5)).isEqualTo(2);
    assertThat(VoiceBenchmarkMetrics.percentile(new double[] {4, 1, 3, 2}, .95)).isEqualTo(4);
    assertThatThrownBy(() -> VoiceBenchmarkMetrics.percentile(new double[] {}, .95))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> VoiceBenchmarkMetrics.percentile(new double[] {Double.NaN}, .95))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> VoiceBenchmarkMetrics.percentile(new double[] {-1}, .95))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
