package dev.mikoto2000.rei.skills;

import dev.mikoto2000.rei.vectordocument.CandidateReranker;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 意味によるスキルの構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SemanticSkillProperties.class)
public class SemanticSkillConfiguration {
  /** スキルの利用で用いるスキル埋め込み表現クライアントを返す。 */
  @Bean(destroyMethod = "close")
  SkillEmbeddingClient skillEmbeddingClient(ObjectProvider<EmbeddingModel> embedding) {
    return new SkillEmbeddingClient(
        () -> {
          var model = embedding.getIfAvailable();
          return model == null ? null : model::embed;
        },
        java.time.Duration.ofSeconds(15));
  }

  /** スキルの利用で用いるスキル埋め込み表現位置を返す。 */
  @Bean
  SkillEmbeddingIndex skillEmbeddingIndex(
      @org.springframework.beans.factory.annotation.Qualifier("dataSource")
          javax.sql.DataSource data) {
    return new SqliteSkillEmbeddingIndex(data);
  }

  /** スキルの利用で用いる意味によるスキル検索を返す。 */
  @Bean
  SemanticSkillSearch semanticSkillSearch(
      SemanticSkillProperties settings,
      SkillEmbeddingClient embedding,
      ObjectProvider<CandidateReranker> reranker,
      SkillEmbeddingIndex index) {
    return new SemanticSkillSearch(
        settings, () -> embedding, reranker::getIfAvailable, System::nanoTime, index);
  }
}
