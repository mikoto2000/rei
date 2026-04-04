package dev.mikoto2000.rei.search;

import java.io.IOException;
import java.util.List;

import org.springframework.stereotype.Service;

import dev.mikoto2000.rei.vectordocument.VectorDocumentSearchResult;
import dev.mikoto2000.rei.vectordocument.VectorDocumentService;
import dev.mikoto2000.rei.websearch.WebSearchContext;
import dev.mikoto2000.rei.websearch.WebSearchOrchestrator;

@Service
public class SearchKnowledgeService {

  private final VectorDocumentService vectorDocumentService;
  private final WebSearchOrchestrator webSearchOrchestrator;

  public SearchKnowledgeService(VectorDocumentService vectorDocumentService, WebSearchOrchestrator webSearchOrchestrator) {
    this.vectorDocumentService = vectorDocumentService;
    this.webSearchOrchestrator = webSearchOrchestrator;
  }

  public SearchKnowledgeResult search(String query, Integer vectorTopK, Integer webTopK, Double threshold, String source)
      throws IOException, InterruptedException {
    List<VectorDocumentSearchResult> vectorResults = vectorDocumentService.search(query, vectorTopK, threshold, source);
    try {
      return new SearchKnowledgeResult(
          query,
          vectorResults,
          webSearchOrchestrator.search(query, webTopK),
          null);
    } catch (IllegalStateException e) {
      return new SearchKnowledgeResult(
          query,
          vectorResults,
          WebSearchContext.primaryOnly(List.of()),
          e.getMessage());
    }
  }
}
