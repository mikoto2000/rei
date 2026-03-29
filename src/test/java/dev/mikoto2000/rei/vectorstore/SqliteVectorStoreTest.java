package dev.mikoto2000.rei.vectorstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import tools.jackson.databind.json.JsonMapper;

class SqliteVectorStoreTest {

  @TempDir
  Path tempDir;

  @Test
  void addSearchAndDeleteDocuments() {
    SqliteVectorStore store = newStore(tempDir.resolve("vector.db"));
    store.add(List.of(
        new Document("doc-1#0", "Spring AI guide", Map.of("docId", "doc-1", "source", "/tmp/spring.md", "chunkIndex", 0, "ingestedAt", "2026-03-29T00:00:00Z")),
        new Document("doc-2#0", "Weather memo for Ibaraki", Map.of("docId", "doc-2", "source", "/tmp/weather.md", "chunkIndex", 0, "ingestedAt", "2026-03-29T00:00:00Z"))));

    List<Document> results = store.similaritySearch(SearchRequest.builder()
        .query("spring ai")
        .topK(3)
        .similarityThresholdAll()
        .build());

    assertEquals(1, results.size());
    assertEquals("doc-1#0", results.getFirst().getId());
    assertEquals("doc-1", results.getFirst().getMetadata().get("docId"));
    assertTrue(results.getFirst().getScore() > 0.9d);

    store.delete(List.of("doc-1#0"));

    List<Document> remaining = store.similaritySearch(SearchRequest.builder()
        .query("spring ai")
        .topK(3)
        .similarityThresholdAll()
        .build());
    assertTrue(remaining.isEmpty());
  }

  @Test
  void similaritySearchHonorsTopKAndThreshold() {
    SqliteVectorStore store = newStore(tempDir.resolve("vector.db"));
    store.add(List.of(
        new Document("doc-1#0", "Spring AI guide", Map.of("docId", "doc-1", "source", "/tmp/spring.md", "chunkIndex", 0, "ingestedAt", "2026-03-29T00:00:00Z")),
        new Document("doc-2#0", "Spring tools memo", Map.of("docId", "doc-2", "source", "/tmp/tools.md", "chunkIndex", 0, "ingestedAt", "2026-03-29T00:00:00Z")),
        new Document("doc-3#0", "Weather memo", Map.of("docId", "doc-3", "source", "/tmp/weather.md", "chunkIndex", 0, "ingestedAt", "2026-03-29T00:00:00Z"))));

    List<Document> topOne = store.similaritySearch(SearchRequest.builder()
        .query("spring")
        .topK(1)
        .similarityThreshold(0.5d)
        .build());

    assertEquals(1, topOne.size());
    assertEquals("doc-1#0", topOne.getFirst().getId());
  }

  @Test
  void similaritySearchAppliesSourceFilterBeforeTopK() {
    SqliteVectorStore store = newStore(tempDir.resolve("vector.db"));
    store.add(List.of(
        new Document("doc-1#0", "Spring memo", Map.of("docId", "doc-1", "source", "/tmp/source-a.md", "chunkIndex", 0, "ingestedAt", "2026-03-29T00:00:00Z")),
        new Document("doc-2#0", "Spring AI memo", Map.of("docId", "doc-2", "source", "/tmp/source-b.md", "chunkIndex", 0, "ingestedAt", "2026-03-29T00:00:00Z"))));

    FilterExpressionBuilder filters = new FilterExpressionBuilder();
    List<Document> results = store.similaritySearch(SearchRequest.builder()
        .query("spring ai")
        .topK(1)
        .similarityThresholdAll()
        .filterExpression(filters.eq("source", "/tmp/source-a.md").build())
        .build());

    assertEquals(1, results.size());
    assertEquals("doc-1#0", results.getFirst().getId());
    assertEquals("/tmp/source-a.md", results.getFirst().getMetadata().get("source"));
  }

  @Test
  void replaceBySourceRollsBackWhenInsertFails() {
    Path dbPath = tempDir.resolve("vector.db");
    SqliteVectorStore goodStore = newStore(dbPath);
    goodStore.replaceBySource(
        "doc-1",
        "/tmp/source-a.md",
        "2026-03-29T00:00:00Z",
        List.of(new Document("doc-1#0", "Spring AI guide", Map.of("docId", "doc-1", "source", "/tmp/source-a.md", "chunkIndex", 0, "ingestedAt", "2026-03-29T00:00:00Z"))));

    SqliteVectorStore failingStore = new SqliteVectorStore(newDataSource(dbPath), new FailingEmbeddingModel(), new JsonMapper());

    assertThrows(IllegalStateException.class, () -> failingStore.replaceBySource(
        "doc-2",
        "/tmp/source-a.md",
        "2026-03-29T00:01:00Z",
        List.of(new Document("doc-2#0", "replacement should fail", Map.of("docId", "doc-2", "source", "/tmp/source-a.md", "chunkIndex", 0, "ingestedAt", "2026-03-29T00:01:00Z")))));

    List<Document> results = goodStore.similaritySearch(SearchRequest.builder()
        .query("spring ai")
        .topK(3)
        .similarityThresholdAll()
        .build());
    assertEquals(1, results.size());
    assertEquals("doc-1#0", results.getFirst().getId());
    assertEquals(1, goodStore.list().size());
  }

  private SqliteVectorStore newStore(Path dbPath) {
    return new SqliteVectorStore(newDataSource(dbPath), new FakeEmbeddingModel(), new JsonMapper());
  }

  private SQLiteDataSource newDataSource(Path dbPath) {
    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + dbPath);
    return dataSource;
  }

  private static class FakeEmbeddingModel implements EmbeddingModel {

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
      List<Embedding> embeddings = java.util.stream.IntStream.range(0, request.getInstructions().size())
          .mapToObj(i -> new Embedding(embedText(request.getInstructions().get(i)), i))
          .toList();
      return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
      return embedText(document.getText());
    }

    @Override
    public int dimensions() {
      return 4;
    }

    protected float[] embedText(String text) {
      String value = text == null ? "" : text.toLowerCase();
      return new float[] {
          score(value, "spring"),
          score(value, "ai"),
          score(value, "weather"),
          score(value, "tools")
      };
    }

    private float score(String value, String token) {
      return value.contains(token) ? 1.0f : 0.0f;
    }
  }

  private static final class FailingEmbeddingModel extends FakeEmbeddingModel {

    @Override
    public float[] embed(Document document) {
      if (document.getText() != null && document.getText().contains("fail")) {
        throw new IllegalStateException("embedding failed");
      }
      return super.embed(document);
    }
  }
}
