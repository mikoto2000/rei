package dev.mikoto2000.rei.vectorstore;

import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

/** 関連文書の検索に必要なHybridRetrieverを扱う。 */
public final class HybridRetriever {
  /** バックエンド。 */
  private final RetrievalCandidates backend;

  /** 設定。 */
  private final HybridRetrievalProperties settings;

  /** 指定された値や利用先を使って、HybridRetrieverを初期化する。 */
  public HybridRetriever(RetrievalCandidates backend, HybridRetrievalProperties settings) {
    this.backend = backend;
    this.settings = settings;
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  public List<Document> search(SearchRequest request) {
    checkCancellation();
    if (request.getTopK() < 1 || request.getTopK() > 256)
      throw new IllegalArgumentException("Hybrid topK must be 1..256");
    if (request.getQuery() == null || request.getQuery().isBlank()) return List.of();
    var builder =
        SearchRequest.builder()
            .query(request.getQuery())
            .topK(Math.max(request.getTopK(), settings.candidateLimit()))
            .similarityThreshold(request.getSimilarityThreshold());
    if (request.hasFilterExpression()) builder.filterExpression(request.getFilterExpression());
    var candidates = builder.build();
    // Validate lexical filters before asking the embedding provider to process the query.
    var lexical =
        settings.bm25Enabled() ? backend.bm25Search(candidates) : backend.lexicalSearch(candidates);
    checkCancellation();
    var dense = backend.denseSearch(candidates);
    checkCancellation();
    return new ReciprocalRankFusion(settings.rankConstant())
        .fuse(List.of(dense, lexical), Document::getId, request.getTopK()).stream()
            .map(
                rank -> {
                  var document = rank.item();
                  var metadata = new LinkedHashMap<>(document.getMetadata());
                  metadata.remove("denseRank");
                  metadata.remove("lexicalRank");
                  metadata.put("retrievalMode", "rrf");
                  metadata.put("rrfScore", rank.score());
                  for (var source : rank.ranks())
                    metadata.put(
                        source.stream() == 0 ? "denseRank" : "lexicalRank", source.position());
                  return Document.builder()
                      .id(document.getId())
                      .text(document.getText())
                      .metadata(metadata)
                      .score(rank.score())
                      .build();
                })
            .toList();
  }

  /** 後続の操作を進められるか判断するため、キャンセルを確認する。 */
  private static void checkCancellation() {
    if (Thread.currentThread().isInterrupted())
      throw new java.util.concurrent.CancellationException("Retrieval cancelled");
  }
}
