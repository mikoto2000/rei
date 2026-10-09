package dev.mikoto2000.rei.voice;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SpeechResultFilterTest {
  @Test void blankPunctuationSlashAndOversizedResultsAreRejected(){
    for(String text:new String[]{"", "  ", "…！？", " /voice off", "あ".repeat(16385)})
      assertThat(SpeechResultFilter.filter(text)).isEmpty();
    assertThat(SpeechResultFilter.filter(null)).isEmpty();
  }
  @Test void validJapaneseAndMixedTermsArePreserved(){
    assertThat(SpeechResultFilter.filter("  Java 25の動作を確認して。  ")).contains("Java 25の動作を確認して。");
  }
}