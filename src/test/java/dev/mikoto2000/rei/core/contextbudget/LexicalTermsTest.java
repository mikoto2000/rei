package dev.mikoto2000.rei.core.contextbudget;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class LexicalTermsTest {
  @Test void sharedTermsPreserveExistingSqliteLexicalSemantics() {
    assertEquals(List.of("spring", "日本語", "25", "http", "get"), LexicalTerms.query("Spring SPRING 日本語 25 HTTP.get A"));
    assertEquals(List.of(), LexicalTerms.query(null)); assertEquals(List.of(), LexicalTerms.query(" "));
  }
}
