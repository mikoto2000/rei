package dev.mikoto2000.rei.memory.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Per legacy memory command/service invocation. Zero retains the existing unlimited behavior. */
@ConfigurationProperties("rei.memory.consolidation")
public record MemoryConsolidationProperties(int maxLlmCalls,long maxTotalTokens) {
  public MemoryConsolidationProperties {
    if(maxLlmCalls<0||maxLlmCalls>1000||maxTotalTokens<0)throw new IllegalArgumentException("Invalid memory consolidation budgets");
  }
}
