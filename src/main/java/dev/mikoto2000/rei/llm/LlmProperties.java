package dev.mikoto2000.rei.llm;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rei.llm")
public class LlmProperties {

  private static final int DEFAULT_MAX_OUTPUT_TOKENS = 8192;

  private Integer maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;
  private OutputLimit outputLimit = new OutputLimit();
  private Map<String, Server> features = new LinkedHashMap<>();

  public Integer getMaxOutputTokens() {
    if (maxOutputTokens == null || maxOutputTokens <= 0) {
      return DEFAULT_MAX_OUTPUT_TOKENS;
    }
    return maxOutputTokens;
  }

  public void setMaxOutputTokens(Integer maxOutputTokens) {
    this.maxOutputTokens = maxOutputTokens;
  }

  public OutputLimit getOutputLimit() {
    return outputLimit;
  }

  public void setOutputLimit(OutputLimit outputLimit) {
    this.outputLimit = outputLimit == null ? new OutputLimit() : outputLimit;
  }

  public Map<String, Server> getFeatures() {
    return features;
  }

  public void setFeatures(Map<String, Server> features) {
    this.features = features == null ? new LinkedHashMap<>() : features;
  }

  public Server feature(String name) {
    return features.get(name);
  }

  public static class Server {
    private String baseUrl;
    private String apiKey;
    private String model;
    private Double temperature;

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public Double getTemperature() {
      return temperature;
    }

    public void setTemperature(Double temperature) {
      this.temperature = temperature;
    }

    public boolean hasCustomServer() {
      return baseUrl != null && !baseUrl.isBlank();
    }
  }

  public static class OutputLimit {
    private static final int DEFAULT_MAX_REPLANS_PER_GOAL = 2;
    private static final int DEFAULT_MAX_SUBGOALS_PER_REPLAN = 8;
    private static final int DEFAULT_MAX_LLM_CALLS_PER_RUN = 30;

    private Integer maxReplansPerGoal = DEFAULT_MAX_REPLANS_PER_GOAL;
    private Integer maxSubgoalsPerReplan = DEFAULT_MAX_SUBGOALS_PER_REPLAN;
    private Integer maxLlmCallsPerRun = DEFAULT_MAX_LLM_CALLS_PER_RUN;

    public Integer getMaxReplansPerGoal() {
      return positiveOrDefault(maxReplansPerGoal, DEFAULT_MAX_REPLANS_PER_GOAL);
    }

    public void setMaxReplansPerGoal(Integer maxReplansPerGoal) {
      this.maxReplansPerGoal = maxReplansPerGoal;
    }

    public Integer getMaxSubgoalsPerReplan() {
      return positiveOrDefault(maxSubgoalsPerReplan, DEFAULT_MAX_SUBGOALS_PER_REPLAN);
    }

    public void setMaxSubgoalsPerReplan(Integer maxSubgoalsPerReplan) {
      this.maxSubgoalsPerReplan = maxSubgoalsPerReplan;
    }

    public Integer getMaxLlmCallsPerRun() {
      return positiveOrDefault(maxLlmCallsPerRun, DEFAULT_MAX_LLM_CALLS_PER_RUN);
    }

    public void setMaxLlmCallsPerRun(Integer maxLlmCallsPerRun) {
      this.maxLlmCallsPerRun = maxLlmCallsPerRun;
    }

    private int positiveOrDefault(Integer value, int defaultValue) {
      return value == null || value <= 0 ? defaultValue : value;
    }
  }
}
