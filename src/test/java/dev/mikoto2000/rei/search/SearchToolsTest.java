package dev.mikoto2000.rei.search;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.tool.annotation.Tool;

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

  @Test
  void descriptionDistinguishesHybridKnowledgeSearchFromPublicWebResearch() throws Exception {
    Tool tool = SearchTools.class.getDeclaredMethod("searchKnowledge", String.class, Integer.class, Integer.class,
        Double.class, String.class, org.springframework.ai.chat.model.ToolContext.class).getAnnotation(Tool.class);

    assertTrue(tool.description().contains("indexed knowledge base"));
    assertTrue(tool.description().contains("supplement"));
    assertTrue(tool.description().contains("public-web-only"));
    assertTrue(tool.description().contains("webSearchAndRead"));
  }

  @Test void renderedWebResultsKeepSelectedRankAcrossPrimaryAndSecondaryClassification() throws Exception {
    var service = Mockito.mock(SearchKnowledgeService.class); var tools = new SearchTools(service);
    var first = new WebSearchPage("first selected", "https://first.example/page", "", null, "first evidence");
    var second = new WebSearchPage("second selected", "https://second.example/page", "", null, "second evidence");
    when(service.search("topic", 1, 2, null, null)).thenReturn(new SearchKnowledgeResult("topic", List.of(),
        new WebSearchContext(List.of(second), List.of(first), List.of(first, second)), null));
    String result = tools.searchKnowledge("topic", 1, 2, null, null);
    assertTrue(result.indexOf(first.url()) < result.indexOf(second.url()));
    assertTrue(result.contains("sourceType=primary")); assertTrue(result.contains("sourceType=secondary"));
    assertTrue(result.contains("first evidence")); assertTrue(result.contains("second evidence"));
    assertTrue(result.indexOf("first evidence") == result.lastIndexOf("first evidence"));
  }
}
