package dev.mikoto2000.rei.memory.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 長期記憶の準備で受け渡す記憶統合設定を表す。
 *
 * <p>Per legacy memory command/service invocation. Zero retains the existing unlimited behavior.
 *
 * @param maxLlmCalls 最大言語モデル呼び出し回数
 * @param maxTotalTokens 最大使用トークン数の合計
 */
@ConfigurationProperties("rei.memory.consolidation")
public record MemoryConsolidationProperties(int maxLlmCalls, long maxTotalTokens) {
  /** 受け取った情報からMemoryConsolidationPropertiesを作成する。 */
  public MemoryConsolidationProperties {
    if (maxLlmCalls < 0 || maxLlmCalls > 1000 || maxTotalTokens < 0)
      throw new IllegalArgumentException("Invalid memory consolidation budgets");
  }
}
