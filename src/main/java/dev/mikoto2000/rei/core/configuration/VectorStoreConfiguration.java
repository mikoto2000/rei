package dev.mikoto2000.rei.core.configuration;

import javax.sql.DataSource;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.mikoto2000.rei.vectorstore.SqliteVectorStore;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class VectorStoreConfiguration {

  @Bean
  public VectorStore vectorStore(
      DataSource dataSource,
      EmbeddingModel embeddingModel,
      JsonMapper objectMapper) {
    return new SqliteVectorStore(dataSource, embeddingModel, objectMapper);
  }
}
