package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LosslessResultPagerTest {
  final TokenEstimator estimator = TokenEstimator.conservative();
  @Test void pagesReassembleJapaneseUnicodeAndEscapedTextExactly() throws Exception {
    String raw = "\uFEFF開始\r\n" + "教材🙂\"\\\t中間の誤り\n".repeat(600) + "末尾";
    var pager = new LosslessResultPager(estimator);
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var assembled = new StringBuilder();
    int offset = 0;
    do {
      String encoded = pager.page("ref", "call", "readMultiFile", raw, offset, 256);
      assertThat(estimator.text(encoded)).isLessThanOrEqualTo(256);
      var page = mapper.readTree(encoded);
      assertThat(page.path("offset").asInt()).isEqualTo(offset);
      assembled.append(page.path("content").asText());
      int next = page.path("nextOffset").asInt();
      assertThat(next).isGreaterThan(offset);
      assertThat(next < raw.length() && Character.isLowSurrogate(raw.charAt(next))).isFalse();
      offset = next;
    } while (offset < raw.length());
    assertThat(assembled.toString()).isEqualTo(raw);
  }
  @Test void metadataAndEscapingAreIncludedAndInsufficientBudgetStops() {
    var pager = new LosslessResultPager(estimator);
    assertThatThrownBy(() -> pager.page("日本語".repeat(100), "call", "tool", "x", 0, 100))
        .hasMessageContaining("CONTEXT_BUDGET_INSUFFICIENT");
  }
  @Test void rejectsInvalidCursorRatherThanBreakingSurrogates() {
    var pager = new LosslessResultPager(estimator);
    for (int offset : new int[]{-1, 2, 5})
      assertThatThrownBy(() -> pager.page("ref", "call", "tool", "a🙂b", offset, 256))
          .hasMessageContaining("INVALID_CURSOR");
  }
}
