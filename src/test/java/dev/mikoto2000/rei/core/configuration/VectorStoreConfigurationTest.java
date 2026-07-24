package dev.mikoto2000.rei.core.configuration;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingResponse;

import dev.mikoto2000.rei.vectordocument.VectorDocumentRepository;
import dev.mikoto2000.rei.vectorstore.LazySqliteVectorStore;
import dev.mikoto2000.rei.vectorstore.SqliteVectorStore;
import tools.jackson.databind.json.JsonMapper;

class VectorStoreConfigurationTest {

  @Test
  void vectorStoreDoesNotInitializeSqliteStoreAtBeanCreation() {
    VectorStoreConfiguration configuration = new VectorStoreConfiguration();
    DataSource dataSource = Mockito.mock(DataSource.class);
    EmbeddingModel embeddingModel = Mockito.mock(EmbeddingModel.class);
    JsonMapper objectMapper = new JsonMapper();

    LazySqliteVectorStore vectorStore = configuration.vectorStore(dataSource, embeddingModel, objectMapper);

    assertTrue(vectorStore instanceof VectorDocumentRepository);
    Mockito.verifyNoInteractions(dataSource, embeddingModel);
  }

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
        .thenReturn(new EmbeddingResponse(java.util.List.of(new Embedding(new float[] {1f, 0f}, 0))));
    Mockito.when(dataSource.getConnection()).thenReturn(connection);
    Mockito.when(connection.createStatement()).thenReturn(statement);

    LazySqliteVectorStore vectorStore = configuration.vectorStore(dataSource, embeddingModel, objectMapper);

    vectorStore.getNativeClient();

    assertSame(dataSource, extractDataSource(vectorStore));
  }

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
