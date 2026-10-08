package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WebContentDeduplicationTest {
  WebPageExtractor extractor = new WebPageExtractor();
  WebSearchResult result(String url) { return new WebSearchResult("title", url, "snippet", "2026-10-09"); }
  @Test void identicalBodiesMergeButKeepBothCitationUrls() {
    var first = extractor.extract(result("https://example.com/a"), "<article>same evidence</article>");
    var second = extractor.extract(result("https://example.org/b"), "<article>same evidence</article>");
    var merged = WebContentDeduplication.pages(List.of(first, second));
    assertEquals(1, merged.size());
    assertEquals(List.of(first.url(), second.url()), merged.getFirst().aliases().stream().map(WebSourceAlias::url).toList());
  }
  @Test void hashIsComputedBeforeOutputTruncation() {
    String prefix = "common prefix ".repeat(200);
    var first = extractor.extract(result("https://example.com/a"), "<article>" + prefix + "first ending</article>");
    var second = extractor.extract(result("https://example.org/b"), "<article>" + prefix + "second ending</article>");
    assertTrue(first.truncated()); assertEquals(first.content(), second.content());
    assertNotEquals(first.fingerprint(), second.fingerprint());
    assertEquals(2, WebContentDeduplication.pages(List.of(first, second)).size());
  }
  @Test void indentationChangesInCodeDoNotBecomeDuplicateBodies() {
    var first = extractor.extract(result("https://example.com/a"), "<pre>if ready:\n    call()\nnext()</pre>");
    var second = extractor.extract(result("https://example.org/b"), "<pre>if ready:\n    call()\n    next()</pre>");
    assertNotEquals(first.fingerprint(), second.fingerprint());
  }
  @Test void snippetsOrLegacyTruncatedPagesAreNotHashedAsFullBodies() {
    var first = new WebSearchPage("a", "https://example.com/a", "same", null, "same", true);
    var second = new WebSearchPage("b", "https://example.org/b", "same", null, "same", true);
    assertEquals(2, WebContentDeduplication.pages(List.of(first, second)).size());
  }
  @Test void canonicalIsAClaimAndDoesNotMergeDifferentBodiesOrCrossOrigins() {
    var first = extractor.extract(result("https://example.com/a"), "<link rel=canonical href=/canonical><p>one</p>");
    var second = extractor.extract(result("https://example.com/b"), "<link rel=canonical href=/canonical><p>two</p>");
    assertEquals(2, WebContentDeduplication.pages(List.of(first, second)).size());
    assertTrue(first.aliases().stream().anyMatch(alias -> "page_canonical_claim".equals(alias.evidenceType())));
    var crossOrigin = extractor.extract(result("https://example.com/a"), "<link rel=canonical href=https://attacker.example/><p>one</p>");
    assertFalse(crossOrigin.aliases().stream().anyMatch(alias -> alias.url().contains("attacker")));
  }
}
