package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.contextbudget.ContextBudgetManager;
import dev.mikoto2000.rei.core.contextbudget.ContextCompressionProperties;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.http.HttpFetchException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Web結果予算の結果と状態の契約を検証するテスト。 */
class WebResultBudgetTest {
  /** Web上の情報の調査で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void perPageBudgetAlsoIncludesResponseContentTypeAndErrors() {
    var budget = WebResultBudget.defaults();
    var item =
        new WebSearchAndReadItem(
            "topic",
            "https://example.com/page",
            "topic",
            null,
            "topic evidence ".repeat(500),
            "text/html; charset=" + "x".repeat(5000),
            "success",
            null,
            null,
            false);
    var fitted = budget.fit(new WebSearchAndReadResponse("topic", List.of(item)), null);
    assertEquals(1, fitted.results().size());
    assertTrue(fitted.results().getFirst().content().contains("evidence"));
    assertTrue(
        TokenEstimator.conservative().text(budget.encoded(fitted.results().getFirst())) <= 1500);
    assertTrue(fitted.results().getFirst().omissions().contains("response_metadata_omitted"));
  }

  /** Web上の情報の調査で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void oversizedPublicationClaimIsOmittedWithoutLosingEvidence() {
    var page =
        new WebSearchPage(
            "topic", "https://example.com/page", "topic", "x".repeat(30000), "topic evidence");
    var fitted =
        budget(1600, 600)
            .fit(
                new WebSearchAndReadResponse(
                    "topic", List.of(WebSearchAndReadItem.fromPage(page, null, null))),
                null);
    assertEquals(1, fitted.results().size());
    assertNull(fitted.results().getFirst().publishedAt());
    assertTrue(fitted.results().getFirst().content().contains("evidence"));
    assertTrue(fitted.results().getFirst().omissions().contains("publication_date_omitted"));
  }

  /** Web上の情報の調査で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void existingConversationConsumesTheAvailableContextBudget() {
    var run =
        org.mockito.Mockito.mock(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.class);
    org.mockito.Mockito.when(run.conversationSnapshot())
        .thenReturn(
            List.of(new org.springframework.ai.chat.messages.UserMessage("日本語".repeat(20000))));
    var tool =
        new org.springframework.ai.chat.model.ToolContext(
            Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY, run));
    assertEquals(0, budget(16000, 6000).tokens(tool));
    org.mockito.Mockito.verify(run).checkModelTokenBudget();
  }

  /** Web上の情報の調査で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void oversizedOptionalAliasDoesNotRemoveTheMainEvidenceSource() {
    var budget = budget(1600, 600);
    var page =
        new WebSearchPage(
            "topic",
            "https://example.com/page",
            "topic",
            null,
            "topic evidence",
            false,
            "hash",
            List.of(
                new WebSourceAlias(
                    "https://example.com/" + "x".repeat(30000),
                    "claimed alias",
                    null,
                    "page_canonical_claim")));
    var fitted =
        budget.fit(
            new WebSearchAndReadResponse(
                "topic", List.of(WebSearchAndReadItem.fromPage(page, null, null))),
            null);
    assertEquals(1, fitted.results().size());
    assertEquals(page.url(), fitted.results().getFirst().url());
    assertTrue(fitted.results().getFirst().content().contains("evidence"));
    assertTrue(fitted.results().getFirst().omissions().contains("citation_aliases_omitted"));
  }

  /** Web上の情報の調査で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void exhaustedContextRejectsBeforeNetworkStarts() {
    var props = new WebSearchProperties();
    var context = new ContextCompressionProperties();
    context.setModelContextLimits(Map.of("small", 12200));
    var budget = new WebResultBudget(props, context, new ContextBudgetManager(128000, 8192, 4000));
    var service =
        org.mockito.Mockito.mock(dev.mikoto2000.rei.urlfetch.UrlContentFetchService.class);
    var tool = new dev.mikoto2000.rei.urlfetch.UrlContentFetchTools(service, budget);
    assertThrows(
        HttpFetchException.class,
        () ->
            tool.fetchUrlContent(
                "https://example.com",
                null,
                new org.springframework.ai.chat.model.ToolContext(Map.of("context", true))));
    org.mockito.Mockito.verifyNoInteractions(service);
  }

  /** {@code budget} の条件を再現するための補助情報を返す。 */
  WebResultBudget budget(int chars, int tokens) {
    var props = new WebSearchProperties();
    props.setMaxOutputCharacters(chars);
    props.setMaxOutputTokens(tokens);
    return new WebResultBudget(
        props, new ContextCompressionProperties(), new ContextBudgetManager(128000, 8192, 4000));
  }

  /** Web上の情報の調査で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void measuresEscapesAndCitationMetadataAndKeepsIntactSource() {
    var budget = budget(1600, 600);
    String url = "https://official.example/reference?v=2.0.1";
    var page =
        new WebSearchPage(
            "API",
            url,
            "summary",
            null,
            "\"\\日本語😀".repeat(1500),
            false,
            "fingerprint",
            List.of(),
            "success",
            null,
            java.time.Instant.EPOCH,
            java.time.Instant.EPOCH,
            List.of(new WebExcerpt("code", List.of("API"), 1, 3000, 9000, "api", 0, 6000, false)),
            List.of(),
            "external_untrusted");
    var response =
        new WebSearchAndReadResponse(
            "API", List.of(WebSearchAndReadItem.fromPage(page, "text/html", null)));
    var fitted = budget.fit(response, null);
    String encoded = budget.encoded(fitted);
    assertTrue(encoded.length() <= 1600);
    assertTrue(TokenEstimator.conservative().text(encoded) <= 600);
    assertEquals(url, fitted.results().getFirst().url());
    assertTrue(fitted.results().getFirst().truncated());
    assertEquals("unknown", fitted.assessment().status());
    assertEquals(java.time.Instant.EPOCH, fitted.results().getFirst().retrievedAt());
    assertTrue(
        fitted.results().getFirst().excerpts().stream()
            .allMatch(
                excerpt -> excerpt.contentEnd() <= fitted.results().getFirst().content().length()));
    assertTrue(encoded.contains("external_untrusted"));
  }

  /** Web上の情報の調査で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void oversizedSourceIsOmittedRatherThanInventingATruncatedCitation() {
    var budget = budget(1600, 600);
    var page =
        new WebSearchPage(
            "title", "https://example.com/" + "x".repeat(30000), "snippet", null, "body");
    var fitted =
        budget.fit(
            new WebSearchAndReadResponse(
                "query", List.of(WebSearchAndReadItem.fromPage(page, null, null))),
            null);
    assertTrue(fitted.results().isEmpty());
    assertTrue(fitted.omissions().contains("source_exceeds_budget"));
    assertTrue(budget.fits(fitted, null));
  }

  /** Web上の情報の調査で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void rawHtmlResultIsBoundedAndExplicitlyPartial() {
    var budget = budget(1600, 600);
    var fitted =
        budget.fit(
            dev.mikoto2000.rei.urlfetch.UrlContentFetchResult.success(
                "日本語".repeat(10000), "text/html", "https://example.com"),
            null);
    assertTrue(fitted.truncated());
    assertTrue(fitted.omissions().contains("output_budget"));
    assertTrue(budget.fits(fitted, null));
    assertEquals("https://example.com", fitted.finalUrl());
  }

  /** 付随情報限定について、URL一覧を維持することを確認する。 */
  @Test
  void metadataOnlyKeepsUrlsAndMarksOmission() {
    var budget = budget(1600, 600);
    String url = "https://example.com/path?q=real";
    var rows =
        budget.fitMetadata(
            List.of(new WebSearchResult("title".repeat(2000), url, "snippet".repeat(2000), null)),
            null);
    assertEquals(url, rows.getFirst().url());
    assertTrue(rows.getFirst().snippet().contains("output_budget"));
    assertTrue(budget.fits(rows, null));
  }

  /** Web上の情報の調査で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void modelContextReserveCanRejectEvenSmallOutput() {
    var props = new WebSearchProperties();
    var context = new ContextCompressionProperties();
    context.setModelContextLimits(Map.of("small", 12200));
    var budget = new WebResultBudget(props, context, new ContextBudgetManager(128000, 8192, 4000));
    assertEquals(0, budget.tokens(null));
    assertEquals(
        HttpFetchException.Code.OUTPUT_BUDGET,
        assertThrows(
                HttpFetchException.class,
                () -> budget.fit(new WebSearchAndReadResponse("q", List.of()), null))
            .code());
  }

  /** Web上の情報の調査で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void knowledgeEntriesAreDroppedWholeIncludingTheirCitation() {
    var budget = budget(600, 256);
    String large = "url=https://example.com/full | content=" + "x".repeat(10000);
    String output =
        budget.fitEntries(
            "retrieval=unknown",
            List.of(large, "\nurl=https://small.example/source | content=evidence"),
            null);
    assertFalse(output.contains("https://example.com"));
    assertTrue(output.contains("https://small.example/source"));
    assertTrue(output.contains("output_budget"));
    assertTrue(budget.fits(output, null));
  }
}
