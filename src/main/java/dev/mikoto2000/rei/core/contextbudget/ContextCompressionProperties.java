package dev.mikoto2000.rei.core.contextbudget;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter @Setter
@ConfigurationProperties("rei.context-compression")
public class ContextCompressionProperties {
  private boolean enabled = true;
  private int modelContextLimit = 128000;
  private java.util.Map<String, Integer> modelContextLimits = new java.util.HashMap<>();
  public int contextLimit(String model) { return model == null ? modelContextLimit : modelContextLimits.getOrDefault(model, modelContextLimit); }
  private int completionReserve = 8192;
  private int toolReserve = 4096;
  private int safetyMargin = 4000;
  private int threshold = 70000;
  private int hardLimit = 85000;
  private int recentTokens = 12000;
  private int summaryTokens = 3000;
  private int toolResultThreshold = 8000;
  private int toolResultTokens = 2000;
  private double minimumCompressionGain = 0.1;
  private int summaryTimeoutSeconds = 60;

  public void validate() {
    if (modelContextLimit <= 0 || completionReserve < 0 || toolReserve < 0 || safetyMargin < 0
        || threshold <= 0 || hardLimit < threshold || recentTokens <= 0 || summaryTokens <= 0
        || toolResultThreshold <= 0 || toolResultTokens <= 0 || toolResultTokens >= toolResultThreshold
        || minimumCompressionGain <= 0 || minimumCompressionGain >= 1 || summaryTimeoutSeconds <= 0)
      throw new IllegalArgumentException("Invalid context compression budgets");
    if ((long) completionReserve + toolReserve + safetyMargin >= modelContextLimit)
      throw new IllegalArgumentException("Context reserves exhaust model context limit");
    if (modelContextLimits == null || modelContextLimits.values().stream().anyMatch(v -> v == null || v <= 0))
      throw new IllegalArgumentException("Invalid model context limits");
  }
}
