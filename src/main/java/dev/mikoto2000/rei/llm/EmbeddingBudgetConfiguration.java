package dev.mikoto2000.rei.llm;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 埋め込み表現予算の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
public class EmbeddingBudgetConfiguration {
  /** 用途に応じたモデル利用で用いる埋め込み表現予算Decoratorを返す。 */
  @Bean
  @ConditionalOnProperty(name = "rei.embedding.inherit-run-model-budget", havingValue = "true")
  static BeanPostProcessor embeddingBudgetDecorator() {
    return new BeanPostProcessor() {
      /** 用途に応じたモデル利用で用いる投稿プロセス変更後初期化を返す。 */
      public Object postProcessAfterInitialization(Object bean, String name) {
        return bean instanceof EmbeddingModel model && !(model instanceof BudgetedEmbeddingModel)
            ? new BudgetedEmbeddingModel(model)
            : bean;
      }
    };
  }
}
