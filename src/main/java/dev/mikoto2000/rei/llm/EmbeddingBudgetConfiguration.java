package dev.mikoto2000.rei.llm;

import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.ai.embedding.EmbeddingModel;

@Configuration(proxyBeanMethods=false)
public class EmbeddingBudgetConfiguration {
  @Bean
  @ConditionalOnProperty(name="rei.embedding.inherit-run-model-budget",havingValue="true")
  static BeanPostProcessor embeddingBudgetDecorator() {
    return new BeanPostProcessor() {
      public Object postProcessAfterInitialization(Object bean,String name) {
        return bean instanceof EmbeddingModel model&&!(model instanceof BudgetedEmbeddingModel)
            ?new BudgetedEmbeddingModel(model):bean;
      }
    };
  }
}
