package dev.mikoto2000.rei.search;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.mikoto2000.rei.vectordocument.VectorDocumentSearchResult;
import dev.mikoto2000.rei.websearch.WebSearchContext;
import dev.mikoto2000.rei.websearch.WebSearchPage;

class SearchToolsTest {

  @Test
  void searchKnowledgeDelegatesToServiceAndFormatsResult() throws Exception {
    SearchKnowledgeService service = Mockito.mock(SearchKnowledgeService.class);
    SearchTools tools = new SearchTools(service);
    when(service.search("spring ai", 3, 5, null, null)).thenReturn(new SearchKnowledgeResult(
        "spring ai",
        List.of(new VectorDocumentSearchResult("doc-1", "/tmp/docs/spec.md", 0, 0.91d, "Spring AI guide")),
        new WebSearchContext(
            List.of(new WebSearchPage("Spring AI Docs", "https://docs.example.com", "snippet", "2026-04-01", "official content")),
            List.of(new WebSearchPage("Blog", "https://example.com/blog", "snippet", null, "secondary content"))),
        null));

    String result = tools.searchKnowledge("spring ai", 3, 5, null, null);

    verify(service).search("spring ai", 3, 5, null, null);
    assertTrue(result.contains("ベクトルストア検索結果"));
    assertTrue(result.contains("Web 一次情報"));
    assertTrue(result.contains("https://docs.example.com"));
    assertTrue(result.contains("secondary content"));
  }
}
