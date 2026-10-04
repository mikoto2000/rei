package dev.mikoto2000.rei.vectordocument;

import java.util.List;
import java.util.function.Function;

/** Reorder an existing candidate set without adding new identities or changing retrieval scores. */
public interface CandidateReranker {
  <T> List<T> rerank(String query,List<T> candidates,Function<T,String> text);
}
