package dev.mikoto2000.rei.core.project;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProjectScopeConfiguration {
  @Bean
  public static BeanFactoryPostProcessor projectScopeRegistrar() {
    return factory -> {
      factory.registerScope("reiProject", new ProjectBeanScope());
      factory.registerScope(
          "reiConversation",
          new ProjectBeanScope(dev.mikoto2000.rei.llm.ConversationIds::currentChat));
    };
  }
}
