package dev.mikoto2000.rei.vectorstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

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

  @Test
  void deleteByDocIdCascadesToChunks() {
    Path dbPath = tempDir.resolve("vector.db");
    SqliteVectorStore store = newStore(dbPath);
    store.add(List.of(
        new Document("doc-1#0", "Spring AI guide", Map.of("docId", "doc-1", "source", "/tmp/spring.md", "chunkIndex", 0, "ingestedAt", "2026-03-29T00:00:00Z")),
        new Document("doc-1#1", "Spring AI appendix", Map.of("docId", "doc-1", "source", "/tmp/spring.md", "chunkIndex", 1, "ingestedAt", "2026-03-29T00:00:00Z"))));

    assertTrue(store.deleteByDocId("doc-1"));
    assertFalse(store.deleteByDocId("doc-1"));
    assertEquals(0, countChunks(dbPath, "doc-1"));
    assertTrue(store.list().isEmpty());
  }

  @Test
  void deleteBySourceReturnsZeroWhenSourceDoesNotExist() {
    SqliteVectorStore store = newStore(tempDir.resolve("vector.db"));
    assertEquals(0, store.deleteBySource("/tmp/missing.md"));
  }

  @Test
  void addRejectsDocumentsWithoutDocId() {
    SqliteVectorStore store = newStore(tempDir.resolve("vector.db"));

    IllegalStateException error = assertThrows(IllegalStateException.class, () -> store.add(List.of(
        new Document("doc-1#0", "Spring AI guide", Map.of("source", "/tmp/spring.md", "chunkIndex", 0, "ingestedAt", "2026-03-29T00:00:00Z")))));

    assertTrue(error.getMessage().contains("docId"));
  }

  @Test
  void storesNormalizedEmbeddings() {
    Path dbPath = tempDir.resolve("vector.db");
    SqliteVectorStore store = newStore(dbPath);
    store.add(List.of(
        new Document("doc-1#0", "Spring AI guide", Map.of("docId", "doc-1", "source", "/tmp/spring.md", "chunkIndex", 0, "ingestedAt", "2026-03-29T00:00:00Z"))));

    float[] embedding = readStoredEmbedding(dbPath, "doc-1#0");
    double norm = 0.0d;
    for (float value : embedding) {
      norm += value * value;
    }

    assertEquals(1.0d, Math.sqrt(norm), 0.0001d);
  }

  @Test
  void migratesLegacyJsonEmbeddingsToBlob() throws Exception {
    Path dbPath = tempDir.resolve("vector.db");
    seedLegacySchema(dbPath, "doc-1#0", "doc-1", "/tmp/spring.md", "2026-03-29T00:00:00Z", """
        {"docId":"doc-1","source":"/tmp/spring.md","chunkIndex":0,"ingestedAt":"2026-03-29T00:00:00Z"}
        """, """
        [0.70710677,0.70710677,0.0,0.0]
        """);

    SqliteVectorStore store = newStore(dbPath);

    float[] embedding = readStoredEmbedding(dbPath, "doc-1#0");
    assertEquals(4, embedding.length);
    assertFalse(hasColumn(dbPath, "document_chunks", "embedding_json"));
    assertTrue(hasColumn(dbPath, "document_chunks", "embedding_blob"));

    List<Document> results = store.similaritySearch(SearchRequest.builder()
        .query("spring ai")
        .topK(3)
        .similarityThresholdAll()
        .build());
    assertEquals(1, results.size());
    assertEquals("doc-1#0", results.getFirst().getId());
  }

  @Test
  void constructorReportsCorruptedDatabaseClearly() {
    IllegalStateException error = assertThrows(IllegalStateException.class,
        () -> new SqliteVectorStore(new FailingDataSource("file is not a database"), new FakeEmbeddingModel(), new JsonMapper()));

    assertTrue(error.getMessage().contains("SQLite ファイルが破損"));
  }

  @Test
  void addReportsLockedDatabaseClearly() {
    SqliteVectorStore store = new SqliteVectorStore(new FailingDataSource("database is locked", false), new FakeEmbeddingModel(), new JsonMapper());

    IllegalStateException error = assertThrows(IllegalStateException.class, () -> store.add(List.of(
        new Document("doc-1#0", "Spring AI guide", Map.of("docId", "doc-1", "source", "/tmp/spring.md", "chunkIndex", 0, "ingestedAt", "2026-03-29T00:00:00Z")))));

    assertTrue(error.getMessage().contains("SQLite がロック"));
  }

  @Test
  void similaritySearchRejectsEmbeddingDimensionMismatch() {
    Path dbPath = tempDir.resolve("vector.db");
    SqliteVectorStore writeStore = newStore(dbPath);
    writeStore.add(List.of(
        new Document("doc-1#0", "Spring AI guide", Map.of("docId", "doc-1", "source", "/tmp/spring.md", "chunkIndex", 0, "ingestedAt", "2026-03-29T00:00:00Z"))));

    SqliteVectorStore readStore = new SqliteVectorStore(newDataSource(dbPath), new ShortEmbeddingModel(), new JsonMapper());

    IllegalStateException error = assertThrows(IllegalStateException.class, () -> readStore.similaritySearch(SearchRequest.builder()
        .query("spring ai")
        .topK(3)
        .similarityThresholdAll()
        .build()));

    assertTrue(error.getMessage().contains("embedding 次元"));
  }

  private SqliteVectorStore newStore(Path dbPath) {
    return new SqliteVectorStore(newDataSource(dbPath), new FakeEmbeddingModel(), new JsonMapper());
  }

  private SQLiteDataSource newDataSource(Path dbPath) {
    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + dbPath);
    return dataSource;
  }

  private float[] readStoredEmbedding(Path dbPath, String chunkId) {
    try (Connection connection = newDataSource(dbPath).getConnection();
        var statement = connection.prepareStatement("SELECT embedding_blob FROM document_chunks WHERE chunk_id = ?")) {
      statement.setString(1, chunkId);
      try (var rs = statement.executeQuery()) {
        if (!rs.next()) {
          throw new IllegalStateException("embedding not found: " + chunkId);
        }
        return decodeEmbedding(rs.getBytes("embedding_blob"));
      }
    } catch (Exception e) {
      throw new IllegalStateException("failed to read embedding", e);
    }
  }

  private float[] decodeEmbedding(byte[] value) {
    ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
    float[] embedding = new float[value.length / Float.BYTES];
    for (int i = 0; i < embedding.length; i++) {
      embedding[i] = buffer.getFloat();
    }
    return embedding;
  }

  private boolean hasColumn(Path dbPath, String tableName, String columnName) {
    try (Connection connection = newDataSource(dbPath).getConnection();
        var statement = connection.prepareStatement("PRAGMA table_info(" + tableName + ")")) {
      try (var rs = statement.executeQuery()) {
        while (rs.next()) {
          if (columnName.equals(rs.getString("name"))) {
            return true;
          }
        }
        return false;
      }
    } catch (Exception e) {
      throw new IllegalStateException("failed to inspect table schema", e);
    }
  }

  private void seedLegacySchema(Path dbPath, String chunkId, String docId, String source, String ingestedAt, String metadataJson,
      String embeddingJson) throws Exception {
    try (Connection connection = newDataSource(dbPath).getConnection(); var statement = connection.createStatement()) {
      statement.executeUpdate("""
          CREATE TABLE documents (
            doc_id TEXT PRIMARY KEY,
            source TEXT NOT NULL,
            ingested_at TEXT
          )
          """);
      statement.executeUpdate("""
          CREATE TABLE document_chunks (
            chunk_id TEXT PRIMARY KEY,
            doc_id TEXT NOT NULL,
            chunk_index INTEGER NOT NULL,
            text TEXT NOT NULL,
            metadata_json TEXT NOT NULL,
            embedding_json TEXT NOT NULL,
            embedding_dim INTEGER NOT NULL,
            FOREIGN KEY (doc_id) REFERENCES documents(doc_id) ON DELETE CASCADE
          )
          """);
      try (var documentStatement = connection.prepareStatement("INSERT INTO documents (doc_id, source, ingested_at) VALUES (?, ?, ?)");
          var chunkStatement = connection.prepareStatement("""
              INSERT INTO document_chunks
                (chunk_id, doc_id, chunk_index, text, metadata_json, embedding_json, embedding_dim)
              VALUES (?, ?, ?, ?, ?, ?, ?)
              """)) {
        documentStatement.setString(1, docId);
        documentStatement.setString(2, source);
        documentStatement.setString(3, ingestedAt);
        documentStatement.executeUpdate();

        chunkStatement.setString(1, chunkId);
        chunkStatement.setString(2, docId);
        chunkStatement.setInt(3, 0);
        chunkStatement.setString(4, "Spring AI guide");
        chunkStatement.setString(5, metadataJson.trim());
        chunkStatement.setString(6, embeddingJson.trim());
        chunkStatement.setInt(7, 4);
        chunkStatement.executeUpdate();
      }
    }
  }

  private int countChunks(Path dbPath, String docId) {
    try (Connection connection = newDataSource(dbPath).getConnection();
        var statement = connection.prepareStatement("SELECT COUNT(*) FROM document_chunks WHERE doc_id = ?")) {
      statement.setString(1, docId);
      try (var rs = statement.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 0;
      }
    } catch (Exception e) {
      throw new IllegalStateException("failed to count chunks", e);
    }
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

  private static final class FailingDataSource implements DataSource {

    private final String message;
    private final boolean failOnInit;
    private int calls;

    private FailingDataSource(String message) {
      this(message, true);
    }

    private FailingDataSource(String message, boolean failOnInit) {
      this.message = message;
      this.failOnInit = failOnInit;
    }

    @Override
    public Connection getConnection() throws SQLException {
      calls++;
      if (!failOnInit && calls == 1) {
        return DriverManager.getConnection("jdbc:sqlite::memory:");
      }
      throw new SQLException(message);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return getConnection();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      throw new SQLException("unwrap unsupported");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }

    @Override
    public java.io.PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(java.io.PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(int seconds) {
    }

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public java.util.logging.Logger getParentLogger() {
      return java.util.logging.Logger.getGlobal();
    }
  }

  private static final class ShortEmbeddingModel extends FakeEmbeddingModel {
    @Override
    public float[] embed(Document document) {
      return new float[] {1.0f, 0.0f};
    }

    @Override
    public int dimensions() {
      return 2;
    }
  }
}
