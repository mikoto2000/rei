package dev.mikoto2000.rei.vectorstore;

import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import dev.mikoto2000.rei.vectordocument.VectorDocumentEntry;
import dev.mikoto2000.rei.vectordocument.VectorDocumentRepository;
import tools.jackson.databind.json.JsonMapper;

public class LazySqliteVectorStore implements VectorStore, VectorDocumentRepository {

  private final DataSource dataSource;
  private final EmbeddingModel embeddingModel;
  private final JsonMapper objectMapper;
  private volatile SqliteVectorStore delegate;

  public LazySqliteVectorStore(DataSource dataSource, EmbeddingModel embeddingModel, JsonMapper objectMapper) {
    this.dataSource = dataSource;
    this.embeddingModel = embeddingModel;
    this.objectMapper = objectMapper;
  }

  @Override
  public void add(List<Document> documents) {
    delegate().add(documents);
  }

  @Override
  public void delete(List<String> ids) {
    delegate().delete(ids);
  }

  @Override
  public void delete(Filter.Expression filterExpression) {
    delegate().delete(filterExpression);
  }

  @Override
  public List<Document> similaritySearch(SearchRequest request) {
    return delegate().similaritySearch(request);
  }

  @Override
  public <T> Optional<T> getNativeClient() {
    return delegate().getNativeClient();
  }

  @Override
  public VectorDocumentEntry replaceBySource(String docId, String source, String ingestedAt, List<Document> documents) {
    return delegate().replaceBySource(docId, source, ingestedAt, documents);
  }

  @Override
  public List<VectorDocumentEntry> list() {
    return delegate().list();
  }

  @Override
  public boolean deleteByDocId(String docId) {
    return delegate().deleteByDocId(docId);
  }

  @Override
  public int deleteBySource(String source) {
    return delegate().deleteBySource(source);
  }

  private SqliteVectorStore delegate() {
    SqliteVectorStore current = delegate;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      if (delegate == null) {
        delegate = new SqliteVectorStore(dataSource, embeddingModel, objectMapper);
      }
      return delegate;
    }
  }
}
