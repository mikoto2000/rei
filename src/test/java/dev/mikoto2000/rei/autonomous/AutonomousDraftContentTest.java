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
}
