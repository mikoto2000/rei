package dev.mikoto2000.rei.core.contextbudget;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 文脈圧縮の設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@Getter
@Setter
@ConfigurationProperties("rei.context-compression")
public class ContextCompressionProperties {
  /** 有効状態。 */
  private boolean enabled = true;

  /** モデルの文脈上限。 */
  private int modelContextLimit = 128000;

  /** モデル文脈上限一覧。 */
  private java.util.Map<String, Integer> modelContextLimits = new java.util.HashMap<>();

  /** モデルへ渡す文脈の管理で用いる文脈上限を返す。 */
  public int contextLimit(String model) {
    return model == null
        ? modelContextLimit
        : modelContextLimits.getOrDefault(model, modelContextLimit);
  }

  /** 完了条件予約。 */
  private int completionReserve = 8192;

  /** ツール予約。 */
  private int toolReserve = 4096;

  /** 概算の誤差に備える余裕。 */
  private int safetyMargin = 4000;

  /** 判定の境界値。 */
  private int threshold = 70000;

  /** 厳しい条件上限。 */
  private int hardLimit = 85000;

  /** 直近トークン数。 */
  private int recentTokens = 12000;

  /** 要約トークン数。 */
  private int summaryTokens = 3000;

  /** ツールの結果判定の境界値。 */
  private int toolResultThreshold = 8000;

  /** ツールの結果トークン数。 */
  private int toolResultTokens = 2000;

  /** 最小圧縮音量の補正。 */
  private double minimumCompressionGain = 0.1;

  /** 要約タイムアウトの秒数。 */
  private int summaryTimeoutSeconds = 60;

  /** 再試行通知を控える期間秒数。 */
  private int retryCooldownSeconds = 120;

  /** 独立した要約最大言語モデル呼び出し回数。 */
  private int standaloneSummaryMaxLlmCalls;

  /** 独立した要約最大使用トークン数の合計。 */
  private long standaloneSummaryMaxTotalTokens;

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  public void validate() {
    if (retryCooldownSeconds < 1 || retryCooldownSeconds > 3600)
      throw new IllegalArgumentException(
          "rei.context-compression.retry-cooldown-seconds must be between 1 and 3600");
    if (modelContextLimit <= 0
        || completionReserve < 0
        || toolReserve < 0
        || safetyMargin < 0
        || threshold <= 0
        || hardLimit < threshold
        || recentTokens <= 0
        || summaryTokens <= 0
        || toolResultThreshold <= 0
        || toolResultTokens <= 0
        || toolResultTokens >= toolResultThreshold
        || minimumCompressionGain <= 0
        || minimumCompressionGain >= 1
        || summaryTimeoutSeconds <= 0
        || standaloneSummaryMaxLlmCalls < 0
        || standaloneSummaryMaxLlmCalls > 1000
        || standaloneSummaryMaxTotalTokens < 0)
      throw new IllegalArgumentException("Invalid context compression budgets");
    if ((long) completionReserve + toolReserve + safetyMargin >= modelContextLimit)
      throw new IllegalArgumentException("Context reserves exhaust model context limit");
    if (modelContextLimits == null
        || modelContextLimits.values().stream().anyMatch(v -> v == null || v <= 0))
      throw new IllegalArgumentException("Invalid model context limits");
  }
}
