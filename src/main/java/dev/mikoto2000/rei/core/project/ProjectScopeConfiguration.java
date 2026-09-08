package dev.mikoto2000.rei.core.project;

import org.springframework.context.annotation.*;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;

@Configuration
public class ProjectScopeConfiguration {
  @Bean
  public static BeanFactoryPostProcessor projectScopeRegistrar() {
    return factory -> factory.registerScope("reiProject", new ProjectBeanScope());
  }
}
