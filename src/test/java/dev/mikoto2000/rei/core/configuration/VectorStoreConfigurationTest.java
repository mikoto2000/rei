package dev.mikoto2000.rei.core.configuration;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.vectordocument.VectorDocumentRepository;
import dev.mikoto2000.rei.vectorstore.LazySqliteVectorStore;
import dev.mikoto2000.rei.vectorstore.SqliteVectorStore;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import tools.jackson.databind.json.JsonMapper;

/** ベクトル保存先機能の構成の結果と状態の契約を検証するテスト。 */
class VectorStoreConfigurationTest {
  /** 機能の組み立てで、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void bm25ConfigurationBindsAndStillInitializesLazily() {
    var properties =
        new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source
                    .MapConfigurationPropertySource(
                    java.util.Map.of(
                        "rei.vector-document.retrieval.enabled",
                        "true",
                        "rei.vector-document.retrieval.bm25-enabled",
                        "true")))
            .bind(
                "rei.vector-document.retrieval",
                dev.mikoto2000.rei.vectorstore.HybridRetrievalProperties.class)
            .get();
    assertTrue(properties.enabled());
    assertTrue(properties.bm25Enabled());
    org.junit.jupiter.api.Assertions.assertEquals(40, properties.candidateLimit());
    var dataSource = Mockito.mock(DataSource.class);
    var model = Mockito.mock(EmbeddingModel.class);
    new VectorStoreConfiguration().vectorStore(dataSource, model, new JsonMapper(), properties);
    Mockito.verifyNoInteractions(dataSource, model);
  }

  /** ベクトルについて、なし初期化SQLite保存先を保存することを確認する。 */
  @Test
  void vectorStoreDoesNotInitializeSqliteStoreAtBeanCreation() {
    VectorStoreConfiguration configuration = new VectorStoreConfiguration();
    DataSource dataSource = Mockito.mock(DataSource.class);
    EmbeddingModel embeddingModel = Mockito.mock(EmbeddingModel.class);
    JsonMapper objectMapper = new JsonMapper();

    LazySqliteVectorStore vectorStore =
        configuration.vectorStore(dataSource, embeddingModel, objectMapper);

    assertTrue(vectorStore instanceof VectorDocumentRepository);
    Mockito.verifyNoInteractions(dataSource, embeddingModel);
  }

  /** 機能の組み立てで、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void vectorStoreUsesDedicatedDataSourceWhenFirstUsed() throws Exception {
    VectorStoreConfiguration configuration = new VectorStoreConfiguration();
    DataSource dataSource = Mockito.mock(DataSource.class);
    EmbeddingModel embeddingModel = Mockito.mock(EmbeddingModel.class);
    Connection connection = Mockito.mock(Connection.class);
    Statement statement = Mockito.mock(Statement.class);
    JsonMapper objectMapper = new JsonMapper();
    Mockito.when(embeddingModel.dimensions()).thenReturn(2);
    Mockito.when(embeddingModel.embedForResponse(Mockito.anyList()))
        .thenReturn(
            new EmbeddingResponse(java.util.List.of(new Embedding(new float[] {1f, 0f}, 0))));
    Mockito.when(dataSource.getConnection()).thenReturn(connection);
    Mockito.when(connection.createStatement()).thenReturn(statement);

    LazySqliteVectorStore vectorStore =
        configuration.vectorStore(dataSource, embeddingModel, objectMapper);

    vectorStore.getNativeClient();

    assertSame(dataSource, extractDataSource(vectorStore));
  }

  /** {@code extractDataSource} の条件を再現するための補助情報を返す。 */
  private DataSource extractDataSource(LazySqliteVectorStore vectorStore) {
    try {
      var delegateField = LazySqliteVectorStore.class.getDeclaredField("delegate");
      delegateField.setAccessible(true);
      SqliteVectorStore delegate = (SqliteVectorStore) delegateField.get(vectorStore);
      var field = SqliteVectorStore.class.getDeclaredField("dataSource");
      field.setAccessible(true);
      return (DataSource) field.get(delegate);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
