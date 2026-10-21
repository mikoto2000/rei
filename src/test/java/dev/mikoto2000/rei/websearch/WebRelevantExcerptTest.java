package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Web関連する根拠として示す抜粋の結果と状態の契約を検証するテスト。 */
class WebRelevantExcerptTest {
  /** 検証に使う情報の抽出。 */
  final WebPageExtractor extractor = new WebPageExtractor();

  /** 検証に使う結果。 */
  final WebSearchResult result =
      new WebSearchResult("Guide", "https://example.com/guide", "", null);

  /** Web上の情報の調査で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void findsLateApiCodeAndKeepsHierarchyPositionsAndIndentation() {
    String html =
        "<h1>Guide</h1><p>"
            + "unrelated introduction ".repeat(300)
            + "</p>"
            + "<h2 id='client'>Client 2.0.1</h2><h3>createResponse API</h3><pre>if (ready) {\n  client.createResponse();\n}</pre>";
    var page = extractor.extract(result, html, "Client 2.0.1 createResponse API");
    assertTrue(page.content().contains("  client.createResponse();"));
    assertTrue(
        page.excerpts().stream()
            .anyMatch(
                section ->
                    section.kind().equals("code") && section.headings().contains("Client 2.0.1")));
    assertTrue(
        page.excerpts().stream()
            .allMatch(section -> section.contentEnd() <= page.content().length()));
    assertTrue(page.excerpts().stream().anyMatch(section -> section.sourceStart() > 2000));
    assertNotNull(page.fingerprint());
    assertEquals("external_untrusted", page.dataTrust());
    assertTrue(page.truncated());
    assertTrue(page.content().length() <= 2000);
  }

  /** 取得元順序を維持することを確認する。 */
  @Test
  void combinesSeparatedEvidenceAndKeepsSourceOrder() {
    var page =
        extractor.extract(
            result,
            "<h2>Alpha configuration</h2><p>alpha setting required.</p><p>noise unrelated</p>"
                + "<h2>Beta verification</h2><table><tr><th>Beta</th><th>Value</th></tr><tr><td>beta mode</td><td>enabled</td></tr></table>",
            "alpha beta");
    assertTrue(page.content().contains("alpha setting"));
    assertTrue(page.content().contains("beta mode"));
    assertTrue(page.excerpts().stream().anyMatch(section -> section.kind().equals("table")));
    assertTrue(page.content().indexOf("alpha") < page.content().indexOf("beta mode"));
  }

  /** Web上の情報の調査で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void fullFingerprintDoesNotBecomeAPrefixHash() {
    var first =
        extractor.extract(
            result, "<p>" + "prefix ".repeat(400) + "</p><p>target evidence A</p>", "target");
    var second =
        extractor.extract(
            result, "<p>" + "prefix ".repeat(400) + "</p><p>target evidence B</p>", "target");
    assertNotEquals(first.fingerprint(), second.fingerprint());
  }

  /** Web上の情報の調査で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void tagDenseInputFailsExplicitlyRatherThanHashingPartialContent() {
    var failure =
        assertThrows(
            dev.mikoto2000.rei.http.HttpFetchException.class,
            () -> extractor.extract(result, "<span>x</span>".repeat(6000), "target"));
    assertEquals(dev.mikoto2000.rei.http.HttpFetchException.Code.EXTRACTION_LIMIT, failure.code());
  }

  /** Web上の情報の調査で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void supplementaryCharactersAreNotSplitByAnExcerptBoundary() {
    var page =
        extractor.extract(result, "<p>" + "🙂".repeat(2000) + "target evidence</p>", "target");
    assertTrue(page.content().contains("target evidence"));
    for (int i = 0; i < page.content().length(); i++)
      if (Character.isHighSurrogate(page.content().charAt(i))) {
        assertTrue(
            i + 1 < page.content().length()
                && Character.isLowSurrogate(page.content().charAt(++i)));
      }
  }
}
