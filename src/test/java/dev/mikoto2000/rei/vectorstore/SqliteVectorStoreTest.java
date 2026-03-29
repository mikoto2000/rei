package dev.mikoto2000.rei.vectorstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import tools.jackson.databind.json.JsonMapper;

class SqliteVectorStoreTest {

  @TempDir
  Path tempDir;

  @Test
  void addSearchAndDeleteDocuments() {
    SqliteVectorStore store = newStore(tempDir.resolve("vector.db"));
    store.add(List.of(
        new Document("doc-1#0", "Spring AI guide", Map.of("docId", "doc-1", "source", "/tmp/spring.md", "chunkIndex", 0)),
        new Document("doc-2#0", "Weather memo for Ibaraki", Map.of("docId", "doc-2", "source", "/tmp/weather.md", "chunkIndex", 0))));

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
        new Document("doc-1#0", "Spring AI guide", Map.of("docId", "doc-1", "source", "/tmp/spring.md", "chunkIndex", 0)),
        new Document("doc-2#0", "Spring tools memo", Map.of("docId", "doc-2", "source", "/tmp/tools.md", "chunkIndex", 0)),
        new Document("doc-3#0", "Weather memo", Map.of("docId", "doc-3", "source", "/tmp/weather.md", "chunkIndex", 0))));

    List<Document> topOne = store.similaritySearch(SearchRequest.builder()
        .query("spring")
        .topK(1)
        .similarityThreshold(0.5d)
        .build());

    assertEquals(1, topOne.size());
    assertEquals("doc-1#0", topOne.getFirst().getId());
  }

  private SqliteVectorStore newStore(Path dbPath) {
    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + dbPath);
    return new SqliteVectorStore(dataSource, new FakeEmbeddingModel(), new JsonMapper());
  }

  private static final class FakeEmbeddingModel implements EmbeddingModel {

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

    private float[] embedText(String text) {
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
}
