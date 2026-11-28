package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AutonomousPostComparisonTest {
  @Test
  void equivalentUnicodeFormsAndFullWidthLettersHaveTheSameComparisonKey() {
    assertThat(AutonomousPostText.duplicateKey("ＡＩ の Cafe\u0301"))
        .isEqualTo(AutonomousPostText.duplicateKey("AI の Café"));
  }

  @Test
  void UnicodeSpacingAndLineEndingDifferencesDoNotCreateAnotherPost() {
    assertThat(AutonomousPostText.duplicateKey("　公開\u00A0資料\r\n の\t話題　"))
        .isEqualTo(AutonomousPostText.duplicateKey("公開 資料 の 話題"));
  }

  @Test
  void punctuationCaseNumbersAndWordBoundariesKeepTheirMeaning() {
    for (var pair :
        List.of(
            List.of("C++", "C"),
            List.of("US", "us"),
            List.of("1.0", "10"),
            List.of("ab c", "abc"),
            List.of("気になる", "気になる？"))) {
      assertThat(AutonomousPostText.duplicateKey(pair.getFirst()))
          .isNotEqualTo(AutonomousPostText.duplicateKey(pair.getLast()));
    }
  }

  @Test
  void comparingDoesNotNormalizeTheReviewedBodyOrItsSecurityHash() {
    var first = AutonomousDraft.Content.create(1, "ＡＩ\r\n資料", "", List.of());
    var second = AutonomousDraft.Content.create(1, "AI 資料", "", List.of());
    assertThat(AutonomousPostText.duplicateKey(first.text()))
        .isEqualTo(AutonomousPostText.duplicateKey(second.text()));
    assertThat(first.text()).isEqualTo("ＡＩ\r\n資料");
    assertThat(first.bodyHash()).isNotEqualTo(second.bodyHash());
  }

  @Test
  void semanticParaphrasesAreNotClaimedToBeEquivalent() {
    assertThat(AutonomousPostText.duplicateKey("Rust の話題"))
        .isNotEqualTo(AutonomousPostText.duplicateKey("Rust について"));
  }
}
