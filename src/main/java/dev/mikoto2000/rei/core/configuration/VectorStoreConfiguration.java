package dev.mikoto2000.rei.core.configuration;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import dev.mikoto2000.rei.vectorstore.DisabledVectorStore;

import dev.mikoto2000.rei.vectorstore.LazySqliteVectorStore;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class VectorStoreConfiguration {

  @Bean
  @ConditionalOnProperty(name = "rei.embedding.enabled", havingValue = "true", matchIfMissing = true)
  public LazySqliteVectorStore vectorStore(
      @Qualifier("vectorStoreDataSource") DataSource dataSource,
      EmbeddingModel embeddingModel,
      JsonMapper objectMapper) {
    return new LazySqliteVectorStore(dataSource, embeddingModel, objectMapper);
  }

  @Bean
  @ConditionalOnProperty(name = "rei.embedding.enabled", havingValue = "false")
  public DisabledVectorStore disabledVectorStore() {
    return new DisabledVectorStore();
  }
}
