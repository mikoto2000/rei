package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 発話結果絞り込み条件の結果と状態の契約を検証するテスト。 */
class SpeechResultFilterTest {
  /** 音声による対話で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void blankPunctuationSlashAndOversizedResultsAreRejected() {
    for (String text : new String[] {"", "  ", "…！？", " /voice off", "あ".repeat(16385)})
      assertThat(SpeechResultFilter.filter(text)).isEmpty();
    assertThat(SpeechResultFilter.filter(null)).isEmpty();
  }

  /** 音声による対話で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void validJapaneseAndMixedTermsArePreserved() {
    assertThat(SpeechResultFilter.filter("  Java 25の動作を確認して。  ")).contains("Java 25の動作を確認して。");
  }
}
