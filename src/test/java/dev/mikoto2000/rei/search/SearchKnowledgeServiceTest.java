package dev.mikoto2000.rei.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.mikoto2000.rei.vectordocument.VectorDocumentSearchResult;
import dev.mikoto2000.rei.vectordocument.VectorDocumentService;
import dev.mikoto2000.rei.websearch.WebSearchContext;
import dev.mikoto2000.rei.websearch.WebSearchOrchestrator;
import dev.mikoto2000.rei.websearch.WebSearchPage;

class SearchKnowledgeServiceTest {

  @Test
  void searchCombinesVectorAndWebResults() throws Exception {
    VectorDocumentService vectorDocumentService = Mockito.mock(VectorDocumentService.class);
    WebSearchOrchestrator webSearchOrchestrator = Mockito.mock(WebSearchOrchestrator.class);
    SearchKnowledgeService service = new SearchKnowledgeService(vectorDocumentService, webSearchOrchestrator);

    when(vectorDocumentService.search("spring ai", 3, 0.4d, "/tmp/docs/spec.md")).thenReturn(List.of(
        new VectorDocumentSearchResult("doc-1", "/tmp/docs/spec.md", 0, 0.91d, "Spring AI guide")));
    when(webSearchOrchestrator.search("spring ai", 2)).thenReturn(new WebSearchContext(
        List.of(new WebSearchPage("Docs", "https://docs.example.com", "snippet", "2026-04-01", "official content")),
        List.of(new WebSearchPage("Blog", "https://example.com/blog", "snippet", null, "secondary content"))));

    SearchKnowledgeResult result = service.search("spring ai", 3, 2, 0.4d, "/tmp/docs/spec.md");

    assertEquals(1, result.vectorResults().size());
    assertEquals(1, result.webContext().primaryResults().size());
    assertEquals(1, result.webContext().secondaryResults().size());
    assertEquals("https://docs.example.com", result.webContext().primaryResults().getFirst().url());
  }

  @Test
  void searchFallsBackToVectorStoreWhenWebSearchFails() throws Exception {
    VectorDocumentService vectorDocumentService = Mockito.mock(VectorDocumentService.class);
    WebSearchOrchestrator webSearchOrchestrator = Mockito.mock(WebSearchOrchestrator.class);
    SearchKnowledgeService service = new SearchKnowledgeService(vectorDocumentService, webSearchOrchestrator);

    when(vectorDocumentService.search("spring ai", 3, null, null)).thenReturn(List.of(
        new VectorDocumentSearchResult("doc-1", "/tmp/docs/spec.md", 0, 0.91d, "Spring AI guide")));
    when(webSearchOrchestrator.search("spring ai", 5)).thenThrow(new IllegalStateException("Web search is disabled"));

    SearchKnowledgeResult result = service.search("spring ai", 3, 5, null, null);

    assertEquals(1, result.vectorResults().size());
    assertTrue(result.webContext().allResults().isEmpty());
    assertEquals("Web search is disabled", result.webSearchSkippedMessage());
  }
}
