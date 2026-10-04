package dev.mikoto2000.rei.vectorstore;

import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

/** Independently ranked candidates. Each implementation must apply filters before topK. */
public interface RetrievalCandidates {
  List<Document> denseSearch(SearchRequest request);
  List<Document> lexicalSearch(SearchRequest request);
}
