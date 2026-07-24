package dev.mikoto2000.rei.core.configuration;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.mikoto2000.rei.vectorstore.LazySqliteVectorStore;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class VectorStoreConfiguration {

  @Bean
  public LazySqliteVectorStore vectorStore(
      @Qualifier("vectorStoreDataSource") DataSource dataSource,
      EmbeddingModel embeddingModel,
      JsonMapper objectMapper) {
    return new LazySqliteVectorStore(dataSource, embeddingModel, objectMapper);
  }
}
