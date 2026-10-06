package dev.mikoto2000.rei.skills;

import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.embedding.EmbeddingModel;
import dev.mikoto2000.rei.vectordocument.CandidateReranker;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(SemanticSkillProperties.class)
public class SemanticSkillConfiguration {
  @Bean(destroyMethod="close") SkillEmbeddingClient skillEmbeddingClient(ObjectProvider<EmbeddingModel> embedding) {
    return new SkillEmbeddingClient(()->{var model=embedding.getIfAvailable();return model==null?null:model::embed;},java.time.Duration.ofSeconds(15));
  }
  @Bean SkillEmbeddingIndex skillEmbeddingIndex(@org.springframework.beans.factory.annotation.Qualifier("dataSource") javax.sql.DataSource data) {
    return new SqliteSkillEmbeddingIndex(data);
  }
  @Bean SemanticSkillSearch semanticSkillSearch(SemanticSkillProperties settings,SkillEmbeddingClient embedding,ObjectProvider<CandidateReranker> reranker,SkillEmbeddingIndex index) {
    return new SemanticSkillSearch(settings,()->embedding,reranker::getIfAvailable,System::nanoTime,index);
  }
}
