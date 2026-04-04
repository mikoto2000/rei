package dev.mikoto2000.rei.vectorstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import dev.mikoto2000.rei.core.configuration.SqliteVecProperties;
import dev.mikoto2000.rei.core.sqlitevec.PlatformDetector;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecAssetResolver;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecDataSource;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecExtensionLoader;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecInstaller;
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

    SqliteVectorStore failingStore = new SqliteVectorStore(newVecDataSource(dbPath), new FailingEmbeddingModel(), new JsonMapper());

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
  void constructorReportsCorruptedDatabaseClearly() {
    IllegalStateException error = assertThrows(IllegalStateException.class,
        () -> new SqliteVectorStore(new FailingDataSource("file is not a database"), new FakeEmbeddingModel(), new JsonMapper()));

    assertTrue(error.getMessage().contains("SQLite ファイルが破損"));
  }

  @Test
  void addReportsLockedDatabaseClearly() {
    DataSource delegate = newVecDataSource(tempDir.resolve("locked.db"));
    DataSource lockedDataSource = new DataSource() {
      private int calls;

      @Override
      public Connection getConnection() throws SQLException {
        calls++;
        if (calls == 1) {
          return delegate.getConnection();
        }
        throw new SQLException("database is locked");
      }

      @Override
      public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
      }

      @Override
      public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
      }

      @Override
      public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
      }

      @Override
      public java.io.PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
      }

      @Override
      public void setLogWriter(java.io.PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
      }

      @Override
      public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
      }

      @Override
      public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
      }

      @Override
      public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
      }
    };
    SqliteVectorStore store = new SqliteVectorStore(lockedDataSource, new FakeEmbeddingModel(), new JsonMapper());

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

    SqliteVectorStore readStore = new SqliteVectorStore(newVecDataSource(dbPath), new ShortEmbeddingModel(), new JsonMapper());

    IllegalStateException error = assertThrows(IllegalStateException.class, () -> readStore.similaritySearch(SearchRequest.builder()
        .query("spring ai")
        .topK(3)
        .similarityThresholdAll()
        .build()));

    assertTrue(error.getMessage().contains("embedding 次元"));
  }

  @Test
  void similaritySearchPrefiltersDocumentsByLexicalMatches() {
    SqliteVectorStore store = new SqliteVectorStore(
        newVecDataSource(tempDir.resolve("vector.db")),
        new ConstantEmbeddingModel(),
        new JsonMapper());
    store.add(List.of(
        new Document("doc-1#0", "alpha beta", Map.of("docId", "doc-1", "source", "/tmp/a.md", "chunkIndex", 0, "ingestedAt", "2026-03-29T00:00:00Z")),
        new Document("doc-2#0", "alpha note", Map.of("docId", "doc-2", "source", "/tmp/b.md", "chunkIndex", 0, "ingestedAt", "2026-03-29T00:00:00Z")),
        new Document("doc-3#0", "weather memo", Map.of("docId", "doc-3", "source", "/tmp/c.md", "chunkIndex", 0, "ingestedAt", "2026-03-29T00:00:00Z"))));

    List<Document> results = store.similaritySearch(SearchRequest.builder()
        .query("alpha beta")
        .topK(5)
        .similarityThresholdAll()
        .build());

    assertEquals(2, results.size());
    assertEquals(List.of("doc-1#0", "doc-2#0"), results.stream().map(Document::getId).toList());
  }

  @Test
  void similaritySearchMixesLexicalScoreIntoRanking() {
    SqliteVectorStore store = new SqliteVectorStore(
        newVecDataSource(tempDir.resolve("vector.db")),
        new ConstantEmbeddingModel(),
        new JsonMapper());
    store.add(List.of(
        new Document("doc-1#0", "alpha beta", Map.of("docId", "doc-1", "source", "/tmp/a.md", "chunkIndex", 0, "ingestedAt", "2026-03-29T00:00:00Z")),
        new Document("doc-2#0", "alpha note", Map.of("docId", "doc-2", "source", "/tmp/b.md", "chunkIndex", 0, "ingestedAt", "2026-03-29T00:00:00Z"))));

    List<Document> results = store.similaritySearch(SearchRequest.builder()
        .query("alpha beta")
        .topK(2)
        .similarityThresholdAll()
        .build());

    assertEquals(2, results.size());
    assertEquals("doc-1#0", results.getFirst().getId());
    assertTrue(results.getFirst().getScore() > results.get(1).getScore());
  }

  @Test
  void sqliteVecModeAddSearchAndDeleteDocuments() {
    SqliteVectorStore store = newStore(tempDir.resolve("vec.db"));
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
  void sqliteVecModeAppliesSourceFilterBeforeRanking() {
    SqliteVectorStore store = newStore(tempDir.resolve("vec.db"));
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
  }

  private SqliteVectorStore newStore(Path dbPath) {
    return new SqliteVectorStore(newVecDataSource(dbPath), new FakeEmbeddingModel(), new JsonMapper());
  }

  private SQLiteDataSource newDataSource(Path dbPath) {
    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + dbPath);
    return dataSource;
  }

  private DataSource newVecDataSource(Path dbPath) {
    SQLiteDataSource delegate = newDataSource(dbPath);
    delegate.setLoadExtension(true);

    SqliteVecProperties properties = new SqliteVecProperties();
    properties.setExtensionPath(vecExtensionPath().toString());
    SqliteVecInstaller installer = new SqliteVecInstaller(
        properties,
        new PlatformDetector(),
        new SqliteVecAssetResolver(),
        new JsonMapper());
    SqliteVecExtensionLoader loader = new SqliteVecExtensionLoader(properties, installer);
    return new SqliteVecDataSource(delegate, loader);
  }

  private Path vecExtensionPath() {
    try {
      Path cacheDir = tempDir.resolve("sqlite-vec-cache");
      SqliteVecProperties properties = new SqliteVecProperties();
      properties.setVersion("0.1.9");
      properties.setAutoDownload(true);
      properties.setCacheDir(cacheDir.toString());
      properties.setReleaseBaseUrl("https://github.com/asg017/sqlite-vec/releases/download");
      SqliteVecInstaller installer = new SqliteVecInstaller(
          properties,
          new PlatformDetector(),
          new SqliteVecAssetResolver(),
          new JsonMapper());
      return installer.resolveExtensionPath();
    } catch (Exception e) {
      throw new IllegalStateException("failed to prepare sqlite-vec extension", e);
    }
  }

  private int countChunks(Path dbPath, String docId) {
    try (Connection connection = newVecDataSource(dbPath).getConnection();
        var statement = connection.prepareStatement("SELECT COUNT(*) FROM document_chunks_vec WHERE doc_id = ?")) {
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

  private static final class ConstantEmbeddingModel extends FakeEmbeddingModel {
    @Override
    public float[] embed(Document document) {
      return new float[] {1.0f, 0.0f, 0.0f, 0.0f};
    }
  }
}
