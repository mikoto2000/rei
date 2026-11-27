package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AutonomousDraftContentTest {
  @Test
  void nullAndBlankTextAreRejectedAsInvalidInput() {
    for (String text : new String[] {null, "", " \n　"}) {
      assertThatThrownBy(() -> AutonomousDraft.Content.create(1, text, "reason", List.of()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void unpairedSurrogatesCannotReuseTheHashOfReplacementText() {
    assertThat(AutonomousDraft.Content.create(1, "?", "reason", List.of()).text()).isEqualTo("?");
    for (String text :
        new String[] {"\uD800", "\uDC00", "before\uD800after", "before\uDC00after"}) {
      assertThatThrownBy(() -> AutonomousDraft.Content.create(1, text, "reason", List.of()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void countsGraphemesRatherThanUtf16CodeUnits() {
    assertThat(AutonomousDraft.Content.create(1, "😀".repeat(300), "reason", List.of()).text())
        .hasSize(600);
    assertThatThrownBy(
            () -> AutonomousDraft.Content.create(1, "😀".repeat(301), "reason", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void byteLimitIsIndependentOfGraphemeLimit() {
    var tooManyBytes = ("a" + "\u0301".repeat(6)).repeat(300);
    assertThatThrownBy(() -> AutonomousDraft.Content.create(1, tooManyBytes, "reason", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void suppliedHashCannotDescribeDifferentText() {
    var content = AutonomousDraft.Content.create(1, "確認済み本文", "reason", List.of("source"));
    assertThatThrownBy(() ->
            new AutonomousDraft.Content(1, "別の本文", content.bodyHash(), "reason", List.of("source")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void terminalAndBidirectionalOverridesCannotSpoofReviewText() {
    for (String text :
        new String[] {
          "safe\u001b[2Jfake review", "a\u0000b", "hidden\roverwrite", "a\u202Eb", "a\u2066b"
        }) {
      assertThatThrownBy(() -> AutonomousDraft.Content.create(1, text, "reason", List.of()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void mentionsAndObfuscatedMentionTokensAreRejected() {
    for (String text :
        new String[] {
          "hi @alice",
          "こんにちは@alice.bsky.social",
          "＠alice.bsky.social",
          "hello\u200B@\u200Balice.bsky.social"
        }) {
      assertThatThrownBy(() -> AutonomousDraft.Content.create(1, text, "reason", List.of()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void ordinaryLinksAndEmailAddressesRemainLiteralText() {
    for (String text :
        new String[] {
          "https://example.org/@profile と person@example.com を紹介します。",
          "Contact person@example.com.",
          "Contact person+tag@example.com, please."
        }) {
      assertThat(AutonomousDraft.Content.create(1, text, "reason", List.of()).text())
          .isEqualTo(text);
    }
  }

  @Test
  void ordinaryWhitespaceAndJoinedEmojiArePreservedExactly() {
    String text = "一行目\r\n二行目\t👩‍💻";
    assertThat(AutonomousDraft.Content.create(1, text, "reason", List.of()).text()).isEqualTo(text);
  }
}
