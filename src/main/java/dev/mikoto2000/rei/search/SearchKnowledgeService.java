package dev.mikoto2000.rei.search;

import dev.mikoto2000.rei.vectordocument.VectorDocumentService;
import dev.mikoto2000.rei.websearch.WebSearchContext;
import dev.mikoto2000.rei.websearch.WebSearchOrchestrator;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Service;

/** 検索知識のユースケースを実現する。 */
@Service
public class SearchKnowledgeService {

  /** ベクトル文書サービス。 */
  private final java.util.function.Function<VectorQuery, SearchKnowledgeResult> searchVectorIndex;

  private record VectorQuery(String query, Integer topK, Double threshold, String source) {}

  /** Web検索Orchestrator。 */
  private final WebSearchOrchestrator webSearchOrchestrator;

  /** 指定された値や利用先を使って、SearchKnowledgeServiceを初期化する。 */
  public SearchKnowledgeService(
      VectorDocumentService vectorDocumentService, WebSearchOrchestrator webSearchOrchestrator) {
    this.searchVectorIndex =
        request ->
            new SearchKnowledgeResult(
                request.query(),
                vectorDocumentService.search(
                    request.query(), request.topK(), request.threshold(), request.source()),
                null,
                null);
    this.webSearchOrchestrator = webSearchOrchestrator;
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  public SearchKnowledgeResult search(
      String query, Integer vectorTopK, Integer webTopK, Double threshold, String source)
      throws IOException, InterruptedException {
    var local = searchVectorIndex.apply(new VectorQuery(query, vectorTopK, threshold, source));
    try {
      return local.withWeb(webSearchOrchestrator.search(query, webTopK), null);
    } catch (IllegalStateException e) {
      dev.mikoto2000.rei.http.FetchOperation.propagateControls(e);
      return local.withWeb(WebSearchContext.primaryOnly(List.of()), e.getMessage());
    }
  }
}
