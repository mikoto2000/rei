package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class SemanticReviewSplitterTest {
  @Test
  void keepsHeadingWithFollowingParagraphAndIgnoresHeadingsInsideFences() {
    String text = "# Start\n" + "text\n".repeat(20) + "\n## Next\n```java\n# not a heading\n\ncode\n```\n\nAfter\n";
    int next = text.indexOf("## Next");
    assertThat(SemanticReviewSplitter.end(text, 0, text.indexOf("code"), text.length())).isEqualTo(next);
    int close = text.indexOf("```\n\nAfter") + 4;
    assertThat(SemanticReviewSplitter.end(text, next, close + 1, text.length())).isEqualTo(close + 1);
  }

  @Test
  void readsWholeFileWhenItFitsAndMakesGaplessProgressThroughOversizedBlocks() {
    String text = "```\n" + "🙂".repeat(500) + "\n```\n";
    assertThat(SemanticReviewSplitter.end(text, 0, text.length(), text.length())).isEqualTo(text.length());
    StringBuilder reconstructed = new StringBuilder();
    for (int start = 0; start < text.length();) {
      int end = SemanticReviewSplitter.end(text, start, Math.min(start + 101, text.length()), text.length());
      assertThat(end).isGreaterThan(start).isLessThanOrEqualTo(start + 101);
      assertThat(Character.isHighSurrogate(text.charAt(end - 1))).isFalse();
      reconstructed.append(text, start, end);
      start = end;
    }
    assertThat(reconstructed.toString()).isEqualTo(text);
  }
}
