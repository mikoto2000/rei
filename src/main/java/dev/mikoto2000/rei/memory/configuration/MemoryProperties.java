package dev.mikoto2000.rei.memory.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rei.memory")
public record MemoryProperties(
    boolean enabled,
    int autoTriggerMessageThreshold,
    int autoTriggerContextPercent,
    int searchMaxResults,
    int searchMaxInjected,
    int summarizeMaxLength,
    int conflictTimeoutSeconds,
    ExpiryDefaults expiry) {

  public MemoryProperties {
    if (autoTriggerMessageThreshold <= 0) {
      autoTriggerMessageThreshold = 20;
    }
    if (autoTriggerContextPercent <= 0) {
      autoTriggerContextPercent = 80;
    }
    if (searchMaxResults <= 0) {
      searchMaxResults = 10;
    }
    if (searchMaxInjected <= 0) {
      searchMaxInjected = 3;
    }
    if (summarizeMaxLength <= 0) {
      summarizeMaxLength = 2000;
    }
    if (conflictTimeoutSeconds <= 0) {
      conflictTimeoutSeconds = 60;
    }
    if (expiry == null) {
      expiry = new ExpiryDefaults(30, 365);
    }
  }

  public record ExpiryDefaults(int shortTermDays, int longTermDays) {
    public ExpiryDefaults {
      if (shortTermDays <= 0) {
        shortTermDays = 30;
      }
      if (longTermDays <= 0) {
        longTermDays = 365;
      }
    }
  }
}
