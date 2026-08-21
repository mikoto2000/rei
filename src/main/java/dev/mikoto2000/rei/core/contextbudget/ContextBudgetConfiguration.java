package dev.mikoto2000.rei.core.contextbudget;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Context Budget Manager の Bean 定義。
 */
@Configuration(proxyBeanMethods = false)
public class ContextBudgetConfiguration {

  static final int DEFAULT_MODEL_CONTEXT_LIMIT = 128000;
  static final int DEFAULT_OUTPUT_RESERVE = 8000;
  static final int DEFAULT_SAFETY_MARGIN = 2000;

  @Bean
  public ContextBudgetManager contextBudgetManager() {
    return new ContextBudgetManager(DEFAULT_MODEL_CONTEXT_LIMIT, DEFAULT_OUTPUT_RESERVE, DEFAULT_SAFETY_MARGIN);
  }
}
