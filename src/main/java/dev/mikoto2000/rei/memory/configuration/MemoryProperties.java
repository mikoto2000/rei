package dev.mikoto2000.rei.memory.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rei.memory")
public record MemoryProperties(
    @org.springframework.boot.context.properties.bind.DefaultValue("true") boolean enabled,
    int autoTriggerMessageThreshold,
    int autoTriggerContextPercent,
    int searchMaxResults,
    int searchMaxInjected,
    int summarizeMaxLength,
    int conflictTimeoutSeconds,
    ExpiryDefaults expiry,
    Retrieval retrieval,
    Sleep sleep) {

  public MemoryProperties(boolean enabled, int autoTriggerMessageThreshold, int autoTriggerContextPercent,
      int searchMaxResults, int searchMaxInjected, int summarizeMaxLength, int conflictTimeoutSeconds, ExpiryDefaults expiry) {
    this(enabled,autoTriggerMessageThreshold,autoTriggerContextPercent,searchMaxResults,searchMaxInjected,
        summarizeMaxLength,conflictTimeoutSeconds,expiry,null,null);
  }

  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public MemoryProperties {
    if (retrieval == null) retrieval = new Retrieval(5,1500);
    if (sleep == null) sleep = new Sleep(.70,.50,50,12000,120);
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

  public record Retrieval(int maxMemories, int maxTokens) {
    public Retrieval { if (maxMemories <= 0) maxMemories=5; if (maxTokens <= 0) maxTokens=1500; }
  }
  public record Sleep(
      @org.springframework.boot.context.properties.bind.DefaultValue("0.70") double minConfidence,
      @org.springframework.boot.context.properties.bind.DefaultValue("0.50") double minImportance,
      int maxTurns, int maxInputTokens, int timeoutSeconds,
      @org.springframework.boot.context.properties.bind.DefaultValue("0") int maxLlmCalls,
      @org.springframework.boot.context.properties.bind.DefaultValue("0") long maxTotalTokens,
      @org.springframework.boot.context.properties.bind.DefaultValue("0") long maxLlmCallsPerProject,
      @org.springframework.boot.context.properties.bind.DefaultValue("0") long maxTotalTokensPerProject) {
    public Sleep(double minConfidence,double minImportance,int maxTurns,int maxInputTokens,int timeoutSeconds,int maxLlmCalls,long maxTotalTokens) {
      this(minConfidence,minImportance,maxTurns,maxInputTokens,timeoutSeconds,maxLlmCalls,maxTotalTokens,0,0);
    }
    public Sleep(double minConfidence,double minImportance,int maxTurns,int maxInputTokens,int timeoutSeconds) {
      this(minConfidence,minImportance,maxTurns,maxInputTokens,timeoutSeconds,0,0);
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public Sleep {
      if(maxLlmCalls<0||maxLlmCalls>1000||maxTotalTokens<0||maxLlmCallsPerProject<0||maxTotalTokensPerProject<0)throw new IllegalArgumentException("Invalid Sleep model budgets");
      if (!Double.isFinite(minConfidence) || minConfidence < 0 || minConfidence > 1
          || !Double.isFinite(minImportance) || minImportance < 0 || minImportance > 1)
        throw new IllegalArgumentException("Invalid memory sleep thresholds");
      if (maxTurns <= 0) maxTurns=50;
      if (maxInputTokens <= 0) maxInputTokens=12000;
      if (timeoutSeconds <= 0) timeoutSeconds=120;
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
