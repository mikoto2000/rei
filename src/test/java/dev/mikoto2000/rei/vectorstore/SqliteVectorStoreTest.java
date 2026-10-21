package dev.mikoto2000.rei.vectorstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.configuration.SqliteVecProperties;
import dev.mikoto2000.rei.core.sqlitevec.PlatformDetector;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecAssetResolver;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecDataSource;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecExtensionLoader;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecInstaller;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.sqlite.SQLiteDataSource;
import tools.jackson.databind.json.JsonMapper;

/** SQLiteベクトル保存先の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class SqliteVectorStoreTest {
  /** 報告された内容費用を維持することを確認する。 */
  @Test
  void embeddingBudgetOvershootRollsBackWholeChunkBatchButKeepsReportedCost() {
    var provider = org.mockito.Mockito.mock(EmbeddingModel.class);
    org.mockito.Mockito.when(provider.dimensions()).thenReturn(2);
    org.mockito.Mockito.when(
            provider.getEmbeddingContent(org.mockito.ArgumentMatchers.any(Document.class)))
        .thenAnswer(invocation -> ((Document) invocation.getArgument(0)).getText());
    org.mockito.Mockito.when(
            provider.call(org.mockito.ArgumentMatchers.any(EmbeddingRequest.class)))
        .thenReturn(
            new EmbeddingResponse(
                List.of(new Embedding(new float[] {1, 0}, 0)),
                new org.springframework.ai.embedding.EmbeddingResponseMetadata(
                    "test", new org.springframework.ai.chat.metadata.DefaultUsage(3, 0))));
    var store =
        new SqliteVectorStore(
            newVecDataSource(tempDir.resolve("budget.db")),
            new dev.mikoto2000.rei.llm.BudgetedEmbeddingModel(provider),
            new JsonMapper());
    var budget = new dev.mikoto2000.rei.llm.OutputLimitRunBudget(0, 10, null, 5);
    var run =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext("run", budget, null, null, null);
    try (var scope = dev.mikoto2000.rei.llm.ModelCallBudgetScope.open(run.modelCallBudget())) {
      assertTrue(
          assertThrows(
                  dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.class,
                  () ->
                      store.add(
                          List.of(chunk("a", "text one", "same"), chunk("b", "text two", "same"))))
              .getMessage()
              .contains("TOKEN_BUDGET_EXCEEDED"));
    }
    assertEquals(6, budget.totalTokens());
    assertTrue(
        store.lexicalSearch(SearchRequest.builder().query("text").topK(10).build()).isEmpty());
    org.mockito.Mockito.verify(provider, org.mockito.Mockito.times(2))
        .call(org.mockito.ArgumentMatchers.any(EmbeddingRequest.class));
  }

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** 追加検索およびについて、保存済み文書を削除することを確認する。 */
  @Test
  void addSearchAndDeleteDocuments() {
    SqliteVectorStore store = newStore(tempDir.resolve("vector.db"));
    store.add(
        List.of(
            new Document(
                "doc-1#0",
                "Spring AI guide",
                Map.of(
                    "docId",
                    "doc-1",
                    "source",
                    "/tmp/spring.md",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-03-29T00:00:00Z")),
            new Document(
                "doc-2#0",
                "Weather memo for Ibaraki",
                Map.of(
                    "docId",
                    "doc-2",
                    "source",
                    "/tmp/weather.md",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-03-29T00:00:00Z"))));

    List<Document> results =
        store.similaritySearch(
            SearchRequest.builder().query("spring ai").topK(3).similarityThresholdAll().build());

    assertEquals(1, results.size());
    assertEquals("doc-1#0", results.getFirst().getId());
    assertEquals("doc-1", results.getFirst().getMetadata().get("docId"));
    assertTrue(results.getFirst().getScore() > 0.9d);

    store.delete(List.of("doc-1#0"));

    List<Document> remaining =
        store.similaritySearch(
            SearchRequest.builder().query("spring ai").topK(3).similarityThresholdAll().build());
    assertTrue(remaining.isEmpty());
  }

  /** 関連文書の検索で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void similaritySearchHonorsTopKAndThreshold() {
    SqliteVectorStore store = newStore(tempDir.resolve("vector.db"));
    store.add(
        List.of(
            new Document(
                "doc-1#0",
                "Spring AI guide",
                Map.of(
                    "docId",
                    "doc-1",
                    "source",
                    "/tmp/spring.md",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-03-29T00:00:00Z")),
            new Document(
                "doc-2#0",
                "Spring tools memo",
                Map.of(
                    "docId",
                    "doc-2",
                    "source",
                    "/tmp/tools.md",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-03-29T00:00:00Z")),
            new Document(
                "doc-3#0",
                "Weather memo",
                Map.of(
                    "docId",
                    "doc-3",
                    "source",
                    "/tmp/weather.md",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-03-29T00:00:00Z"))));

    List<Document> topOne =
        store.similaritySearch(
            SearchRequest.builder().query("spring").topK(1).similarityThreshold(0.5d).build());

    assertEquals(1, topOne.size());
    assertEquals("doc-1#0", topOne.getFirst().getId());
  }

  /** 関連文書の検索で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void similaritySearchAppliesSourceFilterBeforeTopK() {
    SqliteVectorStore store = newStore(tempDir.resolve("vector.db"));
    store.add(
        List.of(
            new Document(
                "doc-1#0",
                "Spring memo",
                Map.of(
                    "docId",
                    "doc-1",
                    "source",
                    "/tmp/source-a.md",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-03-29T00:00:00Z")),
            new Document(
                "doc-2#0",
                "Spring AI memo",
                Map.of(
                    "docId",
                    "doc-2",
                    "source",
                    "/tmp/source-b.md",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-03-29T00:00:00Z"))));

    FilterExpressionBuilder filters = new FilterExpressionBuilder();
    List<Document> results =
        store.similaritySearch(
            SearchRequest.builder()
                .query("spring ai")
                .topK(1)
                .similarityThresholdAll()
                .filterExpression(filters.eq("source", "/tmp/source-a.md").build())
                .build());

    assertEquals(1, results.size());
    assertEquals("doc-1#0", results.getFirst().getId());
    assertEquals("/tmp/source-a.md", results.getFirst().getMetadata().get("source"));
  }

  /** 関連文書の検索で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void replaceBySourceRollsBackWhenInsertFails() {
    Path dbPath = tempDir.resolve("vector.db");
    SqliteVectorStore goodStore = newStore(dbPath);
    goodStore.replaceBySource(
        "doc-1",
        "/tmp/source-a.md",
        "2026-03-29T00:00:00Z",
        List.of(
            new Document(
                "doc-1#0",
                "Spring AI guide",
                Map.of(
                    "docId",
                    "doc-1",
                    "source",
                    "/tmp/source-a.md",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-03-29T00:00:00Z"))));

    SqliteVectorStore failingStore =
        new SqliteVectorStore(
            newVecDataSource(dbPath), new FailingEmbeddingModel(), new JsonMapper());

    assertThrows(
        IllegalStateException.class,
        () ->
            failingStore.replaceBySource(
                "doc-2",
                "/tmp/source-a.md",
                "2026-03-29T00:01:00Z",
                List.of(
                    new Document(
                        "doc-2#0",
                        "replacement should fail",
                        Map.of(
                            "docId",
                            "doc-2",
                            "source",
                            "/tmp/source-a.md",
                            "chunkIndex",
                            0,
                            "ingestedAt",
                            "2026-03-29T00:01:00Z")))));

    List<Document> results =
        goodStore.similaritySearch(
            SearchRequest.builder().query("spring ai").topK(3).similarityThresholdAll().build());
    assertEquals(1, results.size());
    assertEquals("doc-1#0", results.getFirst().getId());
    assertEquals(1, goodStore.list().size());
  }

  /** 関連文書の検索で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void deleteByDocIdCascadesToChunks() {
    Path dbPath = tempDir.resolve("vector.db");
    SqliteVectorStore store = newStore(dbPath);
    store.add(
        List.of(
            new Document(
                "doc-1#0",
                "Spring AI guide",
                Map.of(
                    "docId",
                    "doc-1",
                    "source",
                    "/tmp/spring.md",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-03-29T00:00:00Z")),
            new Document(
                "doc-1#1",
                "Spring AI appendix",
                Map.of(
                    "docId",
                    "doc-1",
                    "source",
                    "/tmp/spring.md",
                    "chunkIndex",
                    1,
                    "ingestedAt",
                    "2026-03-29T00:00:00Z"))));

    assertTrue(store.deleteByDocId("doc-1"));
    assertFalse(store.deleteByDocId("doc-1"));
    assertEquals(0, countChunks(dbPath, "doc-1"));
    assertTrue(store.list().isEmpty());
  }

  /** 削除別取得元について、ゼロを返すことを確認する。 */
  @Test
  void deleteBySourceReturnsZeroWhenSourceDoesNotExist() {
    SqliteVectorStore store = newStore(tempDir.resolve("vector.db"));
    assertEquals(0, store.deleteBySource("/tmp/missing.md"));
  }

  /** 追加について、保存済み文書を受け付けないことを確認する。 */
  @Test
  void addRejectsDocumentsWithoutDocId() {
    SqliteVectorStore store = newStore(tempDir.resolve("vector.db"));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                store.add(
                    List.of(
                        new Document(
                            "doc-1#0",
                            "Spring AI guide",
                            Map.of(
                                "source",
                                "/tmp/spring.md",
                                "chunkIndex",
                                0,
                                "ingestedAt",
                                "2026-03-29T00:00:00Z")))));

    assertTrue(error.getMessage().contains("docId"));
  }

  /** 関連文書の検索で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void constructorReportsCorruptedDatabaseClearly() {
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                new SqliteVectorStore(
                    new FailingDataSource("file is not a database"),
                    new FakeEmbeddingModel(),
                    new JsonMapper()));

    assertTrue(error.getMessage().contains("SQLite ファイルが破損"));
  }

  /** 関連文書の検索で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void addReportsLockedDatabaseClearly() {
    DataSource delegate = newVecDataSource(tempDir.resolve("locked.db"));
    DataSource lockedDataSource =
        new DataSource() {
          /** 検証に使う呼び出し回数。 */
          private int calls;

          /** 検証用に、関連文書の検索で参照する接続を返す。 */
          @Override
          public Connection getConnection() throws SQLException {
            calls++;
            if (calls == 1) {
              return delegate.getConnection();
            }
            throw new SQLException("database is locked");
          }

          /** 検証用に、関連文書の検索で参照する接続を返す。 */
          @Override
          public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
          }

          /** {@code unwrap} の条件を再現するための補助情報を返す。 */
          @Override
          public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
          }

          /** 検証用に、関連文書の検索において、Wrapper対象かどうかを判定する。 */
          @Override
          public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
          }

          /** 検証用に、関連文書の検索で参照するログ書き込み先を返す。 */
          @Override
          public java.io.PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
          }

          /** 検証用に、関連文書の検索で使用するログ書き込み先を設定する。 */
          @Override
          public void setLogWriter(java.io.PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
          }

          /** 検証用に、関連文書の検索で使用するログインタイムアウトを設定する。 */
          @Override
          public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
          }

          /** 検証用に、関連文書の検索で参照するログインタイムアウトを返す。 */
          @Override
          public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
          }

          /** 検証用に、関連文書の検索で参照する親ログの出力先を返す。 */
          @Override
          public java.util.logging.Logger getParentLogger()
              throws java.sql.SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
          }
        };
    SqliteVectorStore store =
        new SqliteVectorStore(lockedDataSource, new FakeEmbeddingModel(), new JsonMapper());

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                store.add(
                    List.of(
                        new Document(
                            "doc-1#0",
                            "Spring AI guide",
                            Map.of(
                                "docId",
                                "doc-1",
                                "source",
                                "/tmp/spring.md",
                                "chunkIndex",
                                0,
                                "ingestedAt",
                                "2026-03-29T00:00:00Z")))));

    assertTrue(error.getMessage().contains("SQLite がロック"));
  }

  /** 類似度検索について、埋め込み表現次元数不一致を受け付けないことを確認する。 */
  @Test
  void similaritySearchRejectsEmbeddingDimensionMismatch() {
    Path dbPath = tempDir.resolve("vector.db");
    SqliteVectorStore writeStore = newStore(dbPath);
    writeStore.add(
        List.of(
            new Document(
                "doc-1#0",
                "Spring AI guide",
                Map.of(
                    "docId",
                    "doc-1",
                    "source",
                    "/tmp/spring.md",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-03-29T00:00:00Z"))));

    SqliteVectorStore readStore =
        new SqliteVectorStore(
            newVecDataSource(dbPath), new ShortEmbeddingModel(), new JsonMapper());

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                readStore.similaritySearch(
                    SearchRequest.builder()
                        .query("spring ai")
                        .topK(3)
                        .similarityThresholdAll()
                        .build()));

    assertTrue(error.getMessage().contains("embedding 次元"));
  }

  /** 関連文書の検索で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void similaritySearchPrefiltersDocumentsByLexicalMatches() {
    SqliteVectorStore store =
        new SqliteVectorStore(
            newVecDataSource(tempDir.resolve("vector.db")),
            new ConstantEmbeddingModel(),
            new JsonMapper());
    store.add(
        List.of(
            new Document(
                "doc-1#0",
                "alpha beta",
                Map.of(
                    "docId",
                    "doc-1",
                    "source",
                    "/tmp/a.md",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-03-29T00:00:00Z")),
            new Document(
                "doc-2#0",
                "alpha note",
                Map.of(
                    "docId",
                    "doc-2",
                    "source",
                    "/tmp/b.md",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-03-29T00:00:00Z")),
            new Document(
                "doc-3#0",
                "weather memo",
                Map.of(
                    "docId",
                    "doc-3",
                    "source",
                    "/tmp/c.md",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-03-29T00:00:00Z"))));

    List<Document> results =
        store.similaritySearch(
            SearchRequest.builder().query("alpha beta").topK(5).similarityThresholdAll().build());

    assertEquals(2, results.size());
    assertEquals(List.of("doc-1#0", "doc-2#0"), results.stream().map(Document::getId).toList());
  }

  /** 関連文書の検索で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void similaritySearchMixesLexicalScoreIntoRanking() {
    SqliteVectorStore store =
        new SqliteVectorStore(
            newVecDataSource(tempDir.resolve("vector.db")),
            new ConstantEmbeddingModel(),
            new JsonMapper());
    store.add(
        List.of(
            new Document(
                "doc-1#0",
                "alpha beta",
                Map.of(
                    "docId",
                    "doc-1",
                    "source",
                    "/tmp/a.md",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-03-29T00:00:00Z")),
            new Document(
                "doc-2#0",
                "alpha note",
                Map.of(
                    "docId",
                    "doc-2",
                    "source",
                    "/tmp/b.md",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-03-29T00:00:00Z"))));

    List<Document> results =
        store.similaritySearch(
            SearchRequest.builder().query("alpha beta").topK(2).similarityThresholdAll().build());

    assertEquals(2, results.size());
    assertEquals("doc-1#0", results.getFirst().getId());
    assertTrue(results.getFirst().getScore() > results.get(1).getScore());
  }

  /** SQLiteベクトル動作モード追加検索およびについて、保存済み文書を削除することを確認する。 */
  @Test
  void sqliteVecModeAddSearchAndDeleteDocuments() {
    SqliteVectorStore store = newStore(tempDir.resolve("vec.db"));
    store.add(
        List.of(
            new Document(
                "doc-1#0",
                "Spring AI guide",
                Map.of(
                    "docId",
                    "doc-1",
                    "source",
                    "/tmp/spring.md",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-03-29T00:00:00Z")),
            new Document(
                "doc-2#0",
                "Weather memo for Ibaraki",
                Map.of(
                    "docId",
                    "doc-2",
                    "source",
                    "/tmp/weather.md",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-03-29T00:00:00Z"))));

    List<Document> results =
        store.similaritySearch(
            SearchRequest.builder().query("spring ai").topK(3).similarityThresholdAll().build());

    assertEquals(1, results.size());
    assertEquals("doc-1#0", results.getFirst().getId());
    assertTrue(results.getFirst().getScore() > 0.9d);

    store.delete(List.of("doc-1#0"));

    List<Document> remaining =
        store.similaritySearch(
            SearchRequest.builder().query("spring ai").topK(3).similarityThresholdAll().build());
    assertTrue(remaining.isEmpty());
  }

  /** 関連文書の検索で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void sqliteVecModeAppliesSourceFilterBeforeRanking() {
    SqliteVectorStore store = newStore(tempDir.resolve("vec.db"));
    store.add(
        List.of(
            new Document(
                "doc-1#0",
                "Spring memo",
                Map.of(
                    "docId",
                    "doc-1",
                    "source",
                    "/tmp/source-a.md",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-03-29T00:00:00Z")),
            new Document(
                "doc-2#0",
                "Spring AI memo",
                Map.of(
                    "docId",
                    "doc-2",
                    "source",
                    "/tmp/source-b.md",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-03-29T00:00:00Z"))));

    FilterExpressionBuilder filters = new FilterExpressionBuilder();
    List<Document> results =
        store.similaritySearch(
            SearchRequest.builder()
                .query("spring ai")
                .topK(1)
                .similarityThresholdAll()
                .filterExpression(filters.eq("source", "/tmp/source-a.md").build())
                .build());

    assertEquals(1, results.size());
    assertEquals("doc-1#0", results.getFirst().getId());
  }

  /** 関連文書の検索で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void constructorDropsLegacyDocumentsTable() throws Exception {
    Path dbPath = tempDir.resolve("legacy.db");
    try (Connection connection = newVecDataSource(dbPath).getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          """
          CREATE TABLE documents (
            doc_id TEXT PRIMARY KEY,
            source TEXT NOT NULL,
            ingested_at TEXT
          )
          """);
    }

    newStore(dbPath);

    try (Connection connection = newVecDataSource(dbPath).getConnection();
        var statement =
            connection.prepareStatement(
                """
            SELECT COUNT(*)
            FROM sqlite_master
            WHERE type = 'table' AND name = 'documents'
            """);
        var rs = statement.executeQuery()) {
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));
    }
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private SqliteVectorStore newStore(Path dbPath) {
    return new SqliteVectorStore(
        newVecDataSource(dbPath), new FakeEmbeddingModel(), new JsonMapper());
  }

  /** 関連文書の検索で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void bm25RanksFrequencyAndLengthAndFiltersBeforeTopK() {
    var store =
        new SqliteVectorStore(
            newVecDataSource(tempDir.resolve("bm25.db")),
            new ConstantEmbeddingModel(),
            new JsonMapper(),
            true);
    store.add(
        List.of(
            chunk("a", "spring " + "filler ".repeat(80), "outside"),
            chunk("b", "spring spring spring", "inside"),
            chunk("c", "spring filler filler filler filler", "inside")));
    var request = SearchRequest.builder().query("spring").topK(3).similarityThresholdAll().build();
    var ranked = store.bm25Search(request);
    assertEquals(List.of("b", "c", "a"), ranked.stream().map(Document::getId).toList());
    assertEquals("bm25", ranked.getFirst().getMetadata().get("lexicalMode"));
    assertTrue(((Number) ranked.getFirst().getMetadata().get("bm25Score")).doubleValue() > 0);
    assertEquals(
        "a",
        store
            .bm25Search(
                SearchRequest.builder()
                    .query("spring")
                    .topK(1)
                    .filterExpression(new FilterExpressionBuilder().eq("source", "outside").build())
                    .build())
            .getFirst()
            .getId());
    assertTrue(
        store
            .bm25Search(
                SearchRequest.builder().query("spring missing").similarityThreshold(0.75).build())
            .isEmpty());
  }

  /** 置換取り消しを維持することを確認する。 */
  @Test
  void bm25BackfillsRestartsAndKeepsReplacementRollbackAndDeletesConsistent() {
    Path path = tempDir.resolve("bm25-restart.db");
    newStore(path).add(List.of(chunk("a", "spring", "one"), chunk("b", "spring", "two")));
    var store =
        new SqliteVectorStore(
            newVecDataSource(path), new ConstantEmbeddingModel(), new JsonMapper(), true);
    assertEquals(2, store.bm25Search(SearchRequest.builder().query("spring").build()).size());
    var failing =
        new SqliteVectorStore(
            newVecDataSource(path), new FailingEmbeddingModel(), new JsonMapper(), true);
    assertThrows(
        IllegalStateException.class,
        () -> failing.replaceBySource("a", "one", "now", List.of(chunk("a", "fail", "one"))));
    assertEquals(2, store.bm25Search(SearchRequest.builder().query("spring").build()).size());
    store.replaceBySource("a", "one", "now", List.of(chunk("a", "weather", "one")));
    assertEquals(
        List.of("b"),
        store.bm25Search(SearchRequest.builder().query("spring").build()).stream()
            .map(Document::getId)
            .toList());
    store.delete(List.of("b"));
    assertTrue(store.bm25Search(SearchRequest.builder().query("spring").build()).isEmpty());
    store.add(List.of(chunk("c", "weather", "three"), chunk("d", "weather", "four")));
    var filters = new FilterExpressionBuilder();
    store.delete(filters.and(filters.eq("docId", "c"), filters.eq("source", "three")).build());
    assertTrue(store.deleteByDocId("d"));
    assertEquals(1, store.deleteBySource("one"));
    assertTrue(store.bm25Search(SearchRequest.builder().query("weather").build()).isEmpty());
    var disabled = newStore(path);
    disabled.add(List.of(chunk("e", "spring", "five")));
    assertEquals(
        "e", store.bm25Search(SearchRequest.builder().query("spring").build()).getFirst().getId());
    disabled.replaceBySource("e", "five", "now", List.of(chunk("e", "spring spring", "five")));
    assertEquals(1, store.bm25Search(SearchRequest.builder().query("spring").build()).size());
    var restarted =
        new SqliteVectorStore(
            newVecDataSource(path), new ConstantEmbeddingModel(), new JsonMapper(), true);
    assertEquals(
        "e",
        restarted.bm25Search(SearchRequest.builder().query("spring").build()).getFirst().getId());
  }

  /** {@code chunk} の条件を再現するための補助情報を返す。 */
  private Document chunk(String id, String text, String source) {
    return new Document(
        id,
        text,
        Map.of(
            "docId", id, "source", source, "chunkIndex", 0, "ingestedAt", "2026-10-06T00:00:00Z"));
  }

  /** 関連文書の検索の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void bm25TreatsOperatorsAsTextAndRejectsUnsupportedFiltersLimitsAndCancellation() {
    var model = org.mockito.Mockito.spy(new ConstantEmbeddingModel());
    var store =
        new SqliteVectorStore(
            newVecDataSource(tempDir.resolve("bm25-query.db")), model, new JsonMapper(), true);
    store.add(List.of(chunk("a", "spring OR weather", "one"), chunk("b", "springtime", "two")));
    org.mockito.Mockito.clearInvocations(model);
    assertEquals(
        List.of("a"),
        store
            .bm25Search(
                SearchRequest.builder().query("\"spring\" OR weather* NOT (missing)").build())
            .stream()
            .map(Document::getId)
            .toList());
    assertEquals(
        List.of("a"),
        store.bm25Search(SearchRequest.builder().query("spring").build()).stream()
            .map(Document::getId)
            .toList());
    assertTrue(store.bm25Search(SearchRequest.builder().query(" ").build()).isEmpty());
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            store.bm25Search(
                SearchRequest.builder()
                    .query("spring")
                    .filterExpression(new FilterExpressionBuilder().eq("secret", "one").build())
                    .build()));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.bm25Search(SearchRequest.builder().query("x".repeat(4097)).build()));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.bm25Search(SearchRequest.builder().query("spring").topK(257).build()));
    try {
      Thread.currentThread().interrupt();
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () -> store.bm25Search(SearchRequest.builder().query("spring").build()));
    } finally {
      Thread.interrupted();
    }
    org.mockito.Mockito.verifyNoInteractions(model);
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            newStore(tempDir.resolve("disabled.db"))
                .bm25Search(SearchRequest.builder().query("spring").build()));
  }

  /** 関連文書の検索で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void independentRetrievalRecoversLexicalMatchOutsideDenseNearestNeighbors() {
    var model =
        new FakeEmbeddingModel() {
          /** {@code embedText} の条件を再現するための補助情報を返す。 */
          @Override
          protected float[] embedText(String text) {
            return text.equals("needle is here")
                ? new float[] {0, 1, 0, 0}
                : new float[] {1, 0, 0, 0};
          }
        };
    var store =
        new SqliteVectorStore(
            newVecDataSource(tempDir.resolve("hybrid.db")), model, new JsonMapper());
    var documents = new java.util.ArrayList<Document>();
    for (int i = 0; i < 25; i++)
      documents.add(
          new Document(
              "dense-" + i,
              "paraphrase " + i,
              Map.of(
                  "docId",
                  "d" + i,
                  "source",
                  "allowed",
                  "chunkIndex",
                  0,
                  "ingestedAt",
                  "2026-10-04")));
    documents.add(
        new Document(
            "lexical",
            "needle is here",
            Map.of(
                "docId", "lex", "source", "allowed", "chunkIndex", 0, "ingestedAt", "2026-10-04")));
    store.add(documents);
    var request =
        SearchRequest.builder()
            .query("needle")
            .topK(1)
            .similarityThresholdAll()
            .filterExpression(new FilterExpressionBuilder().eq("source", "allowed").build())
            .build();
    assertTrue(store.similaritySearch(request).isEmpty());
    assertEquals(1, store.denseSearch(request).size());
    assertEquals("lexical", store.lexicalSearch(request).getFirst().getId());
    var result =
        new HybridRetriever(store, new HybridRetrievalProperties(true, 20, 60))
            .search(
                SearchRequest.builder().query("needle").topK(21).similarityThresholdAll().build());
    assertTrue(result.stream().anyMatch(d -> d.getId().equals("lexical")));
    var filtered =
        SearchRequest.builder()
            .query("needle")
            .topK(1)
            .similarityThresholdAll()
            .filterExpression(new FilterExpressionBuilder().eq("docId", "lex").build())
            .build();
    assertTrue(store.denseSearch(filtered).isEmpty());
    assertEquals(
        List.of("lexical"), store.lexicalSearch(filtered).stream().map(Document::getId).toList());
  }

  /** 語句による候補一覧について、なし検索条件埋め込み表現を必要とすることを確認する。 */
  @Test
  void lexicalCandidatesRequireNoQueryEmbeddingAndRetainSourceBoundary() {
    var model = org.mockito.Mockito.spy(new FakeEmbeddingModel());
    var store =
        new SqliteVectorStore(
            newVecDataSource(tempDir.resolve("lexical.db")), model, new JsonMapper());
    store.add(
        List.of(
            new Document(
                "a",
                "alpha note",
                Map.of(
                    "docId",
                    "a",
                    "source",
                    "allowed",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-10-04")),
            new Document(
                "b",
                "alpha beta",
                Map.of(
                    "docId",
                    "b",
                    "source",
                    "other",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-10-04"))));
    org.mockito.Mockito.clearInvocations(model);
    var request =
        SearchRequest.builder()
            .query("alpha")
            .topK(1)
            .similarityThresholdAll()
            .filterExpression(new FilterExpressionBuilder().eq("source", "allowed").build())
            .build();
    assertEquals("a", store.lexicalSearch(request).getFirst().getId());
    org.mockito.Mockito.verify(model, org.mockito.Mockito.never())
        .embed(org.mockito.ArgumentMatchers.any(Document.class));
    assertTrue(store.denseSearch(request).isEmpty());
    assertEquals(
        "a",
        new HybridRetriever(store, new HybridRetrievalProperties(true, 20, 60))
            .search(request)
            .getFirst()
            .getId());
  }

  /** 関連文書の検索で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void adjacentLexicalEvidenceCannotCrossSourceEvenWhenDocIdWasReused() {
    var store =
        new SqliteVectorStore(
            newVecDataSource(tempDir.resolve("adjacent-scope.db")),
            new ConstantEmbeddingModel(),
            new JsonMapper());
    store.add(
        List.of(
            new Document(
                "a",
                "unrelated",
                Map.of(
                    "docId",
                    "same",
                    "source",
                    "allowed",
                    "chunkIndex",
                    0,
                    "ingestedAt",
                    "2026-10-04")),
            new Document(
                "b",
                "needle",
                Map.of(
                    "docId",
                    "same",
                    "source",
                    "other",
                    "chunkIndex",
                    1,
                    "ingestedAt",
                    "2026-10-04"))));
    var request =
        SearchRequest.builder()
            .query("needle")
            .topK(1)
            .similarityThresholdAll()
            .filterExpression(new FilterExpressionBuilder().eq("source", "allowed").build())
            .build();
    assertTrue(store.lexicalSearch(request).isEmpty());
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private SQLiteDataSource newDataSource(Path dbPath) {
    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + dbPath);
    return dataSource;
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private DataSource newVecDataSource(Path dbPath) {
    SQLiteDataSource delegate = newDataSource(dbPath);
    delegate.setLoadExtension(true);

    SqliteVecProperties properties = new SqliteVecProperties();
    properties.setExtensionPath(vecExtensionPath().toString());
    SqliteVecInstaller installer =
        new SqliteVecInstaller(
            properties, new PlatformDetector(), new SqliteVecAssetResolver(), new JsonMapper());
    SqliteVecExtensionLoader loader = new SqliteVecExtensionLoader(properties, installer);
    return new SqliteVecDataSource(delegate, loader);
  }

  /** {@code vecExtensionPath} の条件を再現するための補助情報を返す。 */
  private Path vecExtensionPath() {
    return dev.mikoto2000.rei.testsupport.SqliteVecTestExtension.resolve();
  }

  /** {@code countChunks} の条件を再現するための補助情報を返す。 */
  private int countChunks(Path dbPath, String docId) {
    try (Connection connection = newVecDataSource(dbPath).getConnection();
        var statement =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM document_chunks_vec WHERE doc_id = ?")) {
      statement.setString(1, docId);
      try (var rs = statement.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 0;
      }
    } catch (Exception e) {
      throw new IllegalStateException("failed to count chunks", e);
    }
  }

  /** 検証条件を再現するための検証用に置き換えた埋め込み表現モデル。 */
  private static class FakeEmbeddingModel implements EmbeddingModel {

    /** 検証用に、関連文書の検索の要求を実行し、呼び出し元へ結果を返す。 */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
      List<Embedding> embeddings =
          java.util.stream.IntStream.range(0, request.getInstructions().size())
              .mapToObj(i -> new Embedding(embedText(request.getInstructions().get(i)), i))
              .toList();
      return new EmbeddingResponse(embeddings);
    }

    /** {@code embed} の条件を再現するための補助情報を返す。 */
    @Override
    public float[] embed(Document document) {
      return embedText(document.getText());
    }

    /** {@code dimensions} の条件を再現するための補助情報を返す。 */
    @Override
    public int dimensions() {
      return 4;
    }

    /** {@code embedText} の条件を再現するための補助情報を返す。 */
    protected float[] embedText(String text) {
      String value = text == null ? "" : text.toLowerCase();
      return new float[] {
        score(value, "spring"), score(value, "ai"), score(value, "weather"), score(value, "tools")
      };
    }

    /** {@code score} の条件を再現するための補助情報を返す。 */
    private float score(String value, String token) {
      return value.contains(token) ? 1.0f : 0.0f;
    }
  }

  /** 検証条件を再現するためのFailing埋め込み表現モデル。 */
  private static final class FailingEmbeddingModel extends FakeEmbeddingModel {

    /** {@code embed} の条件を再現するための補助情報を返す。 */
    @Override
    public float[] embed(Document document) {
      if (document.getText() != null && document.getText().contains("fail")) {
        throw new IllegalStateException("embedding failed");
      }
      return super.embed(document);
    }
  }

  /** 検証条件を再現するためのFailingデータ取得元。 */
  private static final class FailingDataSource implements DataSource {

    /** 検証に使うメッセージ。 */
    private final String message;

    /** 検証に使うfail通知時初期化。 */
    private final boolean failOnInit;

    /** 検証に使う呼び出し回数。 */
    private int calls;

    /** 検証条件に必要な値を受け取り、FailingDataSourceを用意する。 */
    private FailingDataSource(String message) {
      this(message, true);
    }

    /** 検証条件に必要な値を受け取り、FailingDataSourceを用意する。 */
    private FailingDataSource(String message, boolean failOnInit) {
      this.message = message;
      this.failOnInit = failOnInit;
    }

    /** 検証用に、関連文書の検索で参照する接続を返す。 */
    @Override
    public Connection getConnection() throws SQLException {
      calls++;
      if (!failOnInit && calls == 1) {
        return DriverManager.getConnection("jdbc:sqlite::memory:");
      }
      throw new SQLException(message);
    }

    /** 検証用に、関連文書の検索で参照する接続を返す。 */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return getConnection();
    }

    /** {@code unwrap} の条件を再現するための補助情報を返す。 */
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      throw new SQLException("unwrap unsupported");
    }

    /** 検証用に、関連文書の検索において、Wrapper対象かどうかを判定する。 */
    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }

    /** 検証用に、関連文書の検索で参照するログ書き込み先を返す。 */
    @Override
    public java.io.PrintWriter getLogWriter() {
      return null;
    }

    /** 検証用に、関連文書の検索で使用するログ書き込み先を設定する。 */
    @Override
    public void setLogWriter(java.io.PrintWriter out) {}

    /** 検証用に、関連文書の検索で使用するログインタイムアウトを設定する。 */
    @Override
    public void setLoginTimeout(int seconds) {}

    /** 検証用に、関連文書の検索で参照するログインタイムアウトを返す。 */
    @Override
    public int getLoginTimeout() {
      return 0;
    }

    /** 検証用に、関連文書の検索で参照する親ログの出力先を返す。 */
    @Override
    public java.util.logging.Logger getParentLogger() {
      return java.util.logging.Logger.getGlobal();
    }
  }

  /** 検証条件を再現するための短い埋め込み表現モデル。 */
  private static final class ShortEmbeddingModel extends FakeEmbeddingModel {
    /** {@code embed} の条件を再現するための補助情報を返す。 */
    @Override
    public float[] embed(Document document) {
      return new float[] {1.0f, 0.0f};
    }

    /** {@code dimensions} の条件を再現するための補助情報を返す。 */
    @Override
    public int dimensions() {
      return 2;
    }
  }

  /** 検証条件を再現するための固定値埋め込み表現モデル。 */
  private static final class ConstantEmbeddingModel extends FakeEmbeddingModel {
    /** {@code embed} の条件を再現するための補助情報を返す。 */
    @Override
    public float[] embed(Document document) {
      return new float[] {1.0f, 0.0f, 0.0f, 0.0f};
    }
  }
}
