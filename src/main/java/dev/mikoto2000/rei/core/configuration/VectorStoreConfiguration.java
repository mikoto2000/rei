package dev.mikoto2000.rei.core.configuration;

import dev.mikoto2000.rei.vectorstore.DisabledVectorStore;
import dev.mikoto2000.rei.vectorstore.HybridRetrievalProperties;
import dev.mikoto2000.rei.vectorstore.LazySqliteVectorStore;
import javax.sql.DataSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** ベクトル保存先の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration
@EnableConfigurationProperties(HybridRetrievalProperties.class)
public class VectorStoreConfiguration {

  /** 機能の組み立てで用いるベクトル保存先を返す。 */
  @Bean
  @ConditionalOnProperty(
      name = "rei.embedding.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public LazySqliteVectorStore vectorStore(
      @Qualifier("vectorStoreDataSource") DataSource dataSource,
      EmbeddingModel embeddingModel,
      JsonMapper objectMapper,
      HybridRetrievalProperties retrieval) {
    return new LazySqliteVectorStore(
        dataSource, embeddingModel, objectMapper, retrieval.enabled() && retrieval.bm25Enabled());
  }

  /** 機能の組み立てで用いるベクトル保存先を返す。 */
  public LazySqliteVectorStore vectorStore(
      DataSource dataSource, EmbeddingModel embeddingModel, JsonMapper objectMapper) {
    return vectorStore(
        dataSource, embeddingModel, objectMapper, new HybridRetrievalProperties(false, 40, 60));
  }

  /** 機能の組み立てで用いる無効状態ベクトル保存先を返す。 */
  @Bean
  @ConditionalOnProperty(name = "rei.embedding.enabled", havingValue = "false")
  public DisabledVectorStore disabledVectorStore() {
    return new DisabledVectorStore();
  }
}
