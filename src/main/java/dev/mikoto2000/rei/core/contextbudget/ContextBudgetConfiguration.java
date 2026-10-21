package dev.mikoto2000.rei.core.contextbudget;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Context Budget Manager の Bean 定義。 */
@Configuration(proxyBeanMethods = false)
public class ContextBudgetConfiguration {

  /** 既定モデルの文脈上限。 */
  static final int DEFAULT_MODEL_CONTEXT_LIMIT = 128000;

  /** 既定回答用に確保する予算。 */
  static final int DEFAULT_OUTPUT_RESERVE = 8000;

  /** 既定概算の誤差に備える余裕。 */
  static final int DEFAULT_SAFETY_MARGIN = 2000;

  /** モデルへ渡す文脈の管理で用いる文脈に使える予算管理を返す。 */
  @Bean
  public ContextBudgetManager contextBudgetManager() {
    return new ContextBudgetManager(
        DEFAULT_MODEL_CONTEXT_LIMIT, DEFAULT_OUTPUT_RESERVE, DEFAULT_SAFETY_MARGIN);
  }
}
