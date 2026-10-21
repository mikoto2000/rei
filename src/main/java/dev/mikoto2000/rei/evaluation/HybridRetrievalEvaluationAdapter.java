package dev.mikoto2000.rei.evaluation;

import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.vectordocument.CandidateReranker;
import dev.mikoto2000.rei.vectorstore.HybridRetrievalProperties;
import dev.mikoto2000.rei.vectorstore.HybridRetriever;
import dev.mikoto2000.rei.vectorstore.RetrievalCandidates;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

/**
 * Hybrid関連情報の取得評価を別の利用先へ接続する。
 *
 * <p>Calls the actual lexical/dense/RRF components without changing production search defaults.
 */
public final class HybridRetrievalEvaluationAdapter
    implements RetrievalQualityEvaluation.Retriever {
  /** バックエンド。 */
  private final RetrievalCandidates backend;

  /** 設定。 */
  private final HybridRetrievalProperties settings;

  /** 候補の関連度を再評価する窓口。 */
  private final CandidateReranker reranker;

  /** 指定された値や利用先を使って、HybridRetrievalEvaluationAdapterを初期化する。 */
  public HybridRetrievalEvaluationAdapter(
      RetrievalCandidates backend, HybridRetrievalProperties settings, CandidateReranker reranker) {
    this.backend = Objects.requireNonNull(backend);
    this.settings = Objects.requireNonNull(settings);
    this.reranker = reranker;
  }

  /** 成果の評価で用いる取得を返す。 */
  public List<String> retrieve(
      RetrievalQualityEvaluation.Case fixture,
      RetrievalQualityEvaluation.Mode mode,
      boolean rerank) {
    RunCancellation.propagate(null);
    var request =
        SearchRequest.builder()
            .query(fixture.query())
            .topK(Math.min(256, Math.max(fixture.topK(), settings.candidateLimit())))
            .build();
    List<Document> results =
        switch (mode) {
          case LEXICAL ->
              settings.bm25Enabled() ? backend.bm25Search(request) : backend.lexicalSearch(request);
          case DENSE -> backend.denseSearch(request);
          case RRF -> new HybridRetriever(backend, settings).search(request);
        };
    if (results == null
        || results.size() > 256
        || results.stream().anyMatch(Objects::isNull)
        || results.stream().map(Document::getId).distinct().count() != results.size())
      throw new IllegalStateException("Invalid retrieval candidates");
    var original = new LinkedHashMap<String, Document>();
    results.forEach(doc -> original.put(doc.getId(), doc));
    if (rerank) {
      if (reranker == null)
        throw new IllegalStateException("Rerank evaluation provider unavailable");
      var ranked = reranker.rerankForEvaluation(fixture.query(), results, Document::getText);
      RunCancellation.propagate(null);
      if (ranked == null
          || ranked.size() != results.size()
          || ranked.stream().anyMatch(Objects::isNull)
          || !original
              .keySet()
              .equals(new HashSet<>(ranked.stream().map(Document::getId).toList())))
        throw new IllegalStateException("Reranker changed candidate identities");
      results = ranked;
    }
    return results.stream().limit(fixture.topK()).map(Document::getId).toList();
  }
}
