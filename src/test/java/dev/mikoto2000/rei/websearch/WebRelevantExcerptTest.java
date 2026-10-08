package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class WebRelevantExcerptTest {
  final WebPageExtractor extractor = new WebPageExtractor();
  final WebSearchResult result = new WebSearchResult("Guide", "https://example.com/guide", "", null);
  @Test void findsLateApiCodeAndKeepsHierarchyPositionsAndIndentation() {
    String html = "<h1>Guide</h1><p>" + "unrelated introduction ".repeat(300) + "</p>"
        + "<h2 id='client'>Client 2.0.1</h2><h3>createResponse API</h3><pre>if (ready) {\n  client.createResponse();\n}</pre>";
    var page = extractor.extract(result, html, "Client 2.0.1 createResponse API");
    assertTrue(page.content().contains("  client.createResponse();"));
    assertTrue(page.excerpts().stream().anyMatch(section -> section.kind().equals("code") && section.headings().contains("Client 2.0.1")));
    assertTrue(page.excerpts().stream().allMatch(section -> section.contentEnd() <= page.content().length()));
    assertTrue(page.excerpts().stream().anyMatch(section -> section.sourceStart() > 2000));
    assertNotNull(page.fingerprint()); assertEquals("external_untrusted", page.dataTrust());
    assertTrue(page.truncated()); assertTrue(page.content().length() <= 2000);
  }
  @Test void combinesSeparatedEvidenceAndKeepsSourceOrder() {
    var page = extractor.extract(result, "<h2>Alpha configuration</h2><p>alpha setting required.</p><p>noise unrelated</p>"
        + "<h2>Beta verification</h2><table><tr><th>Beta</th><th>Value</th></tr><tr><td>beta mode</td><td>enabled</td></tr></table>", "alpha beta");
    assertTrue(page.content().contains("alpha setting")); assertTrue(page.content().contains("beta mode"));
    assertTrue(page.excerpts().stream().anyMatch(section -> section.kind().equals("table")));
    assertTrue(page.content().indexOf("alpha") < page.content().indexOf("beta mode"));
  }
  @Test void fullFingerprintDoesNotBecomeAPrefixHash() {
    var first = extractor.extract(result, "<p>" + "prefix ".repeat(400) + "</p><p>target evidence A</p>", "target");
    var second = extractor.extract(result, "<p>" + "prefix ".repeat(400) + "</p><p>target evidence B</p>", "target");
    assertNotEquals(first.fingerprint(), second.fingerprint());
  }
  @Test void tagDenseInputFailsExplicitlyRatherThanHashingPartialContent() {
    var failure = assertThrows(dev.mikoto2000.rei.http.HttpFetchException.class,
        () -> extractor.extract(result, "<span>x</span>".repeat(6000), "target"));
    assertEquals(dev.mikoto2000.rei.http.HttpFetchException.Code.EXTRACTION_LIMIT, failure.code());
  }
  @Test void supplementaryCharactersAreNotSplitByAnExcerptBoundary() {
    var page = extractor.extract(result, "<p>" + "🙂".repeat(2000) + "target evidence</p>", "target");
    assertTrue(page.content().contains("target evidence"));
    for (int i = 0; i < page.content().length(); i++) if (Character.isHighSurrogate(page.content().charAt(i))) {
      assertTrue(i + 1 < page.content().length() && Character.isLowSurrogate(page.content().charAt(++i)));
    }
  }
}
