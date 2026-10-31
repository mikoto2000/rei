package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;

class SourcePositionsTest {
  private static int[] reference(String source, int offset) {
    int line = 1, beginning = 0;
    for (int i = 0; i < offset; i++)
      if (source.charAt(i) == '\n') {
        line++;
        beginning = i + 1;
      }
    return new int[] {line, offset - beginning};
  }

  @Test
  void everyUtf16PositionMatchesDecodedSourceIncludingCrLfBomAndSurrogates() {
    var random = new Random(419);
    var pieces = new String[] {"\uFEFF", "\r\n", "\n", "\r", "🙂", "日本語", "abc", ""};
    for (int sample = 0; sample < 64; sample++) {
      var text = new StringBuilder();
      for (int i = 0; i < 150; i++) text.append(pieces[random.nextInt(pieces.length)]);
      String source = text.toString();
      var positions = new SourcePositions(source);
      for (int offset = 0; offset <= source.length(); offset++)
        assertThat(positions.at(offset))
            .as("sample %s offset %s", sample, offset)
            .containsExactly(reference(source, offset));
    }
  }

  @Test
  void emptyAndFinalNewlineHaveExactEndCoordinates() {
    assertThat(new SourcePositions("").at(0)).containsExactly(1, 0);
    assertThat(new SourcePositions("a\r\n").at(2)).containsExactly(1, 2);
    assertThat(new SourcePositions("a\r\n").at(3)).containsExactly(2, 0);
    assertThat(new SourcePositions("🙂").at(2)).containsExactly(1, 2);
  }
}
