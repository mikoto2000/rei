package dev.mikoto2000.rei.vectorstore;

import java.util.List;
import java.util.Optional;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import dev.mikoto2000.rei.vectordocument.VectorDocumentEntry;
import dev.mikoto2000.rei.vectordocument.VectorDocumentRepository;

/** Provides empty retrieval results without a database or embedding model. */
public class DisabledVectorStore implements VectorStore, VectorDocumentRepository {
  @Override
  public List<Document> similaritySearch(SearchRequest request) {
    return List.of();
  }

  @Override
  public <T> Optional<T> getNativeClient() {
    return Optional.empty();
  }

  @Override
  public void add(List<Document> documents) {
    throw disabled();
  }

  @Override
  public void delete(List<String> ids) {
    throw disabled();
  }

  @Override
  public void delete(Filter.Expression filterExpression) {
    throw disabled();
  }

  @Override
  public VectorDocumentEntry replaceBySource(String docId, String source, String ingestedAt, List<Document> documents) {
    throw disabled();
  }

  @Override
  public List<VectorDocumentEntry> list() {
    throw disabled();
  }

  @Override
  public boolean deleteByDocId(String docId) {
    throw disabled();
  }

  @Override
  public int deleteBySource(String source) {
    throw disabled();
  }

  private IllegalStateException disabled() {
    return new IllegalStateException("embedding は無効です (rei.embedding.enabled=false)");
  }
}
