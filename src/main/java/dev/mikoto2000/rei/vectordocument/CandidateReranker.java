package dev.mikoto2000.rei.vectordocument;

import java.util.List;
import java.util.function.Function;

/**
 * 検索対象文書の管理に必要な候補Rerankerを扱う。
 *
 * <p>Reorder an existing candidate set without adding new identities or changing retrieval scores.
 */
public interface CandidateReranker {
  /** 検索対象文書の管理で用いる再評価を返す。 */
  <T> List<T> rerank(String query, List<T> candidates, Function<T, String> text);

  /**
   * 検索対象文書の管理で用いる再評価対象評価を返す。
   *
   * <p>Evaluation providers must expose failure instead of silently substituting the original
   * order.
   */
  default <T> List<T> rerankForEvaluation(
      String query, List<T> candidates, Function<T, String> text) {
    return rerank(query, candidates, text);
  }
}
