package dev.mikoto2000.rei.vectordocument;

import java.util.List;
import java.util.function.Function;

/** Reorder an existing candidate set without adding new identities or changing retrieval scores. */
public interface CandidateReranker {
  <T> List<T> rerank(String query,List<T> candidates,Function<T,String> text);
  /** Evaluation providers must expose failure instead of silently substituting the original order. */
  default <T> List<T> rerankForEvaluation(String query,List<T> candidates,Function<T,String> text){return rerank(query,candidates,text);}
}
