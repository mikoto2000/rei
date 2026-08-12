package dev.mikoto2000.rei.llm;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rei.llm")
public class LlmProperties {

  private Map<String, Server> features = new LinkedHashMap<>();

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

    boolean hasCustomServer() {
      return baseUrl != null && !baseUrl.isBlank();
    }
  }
}
