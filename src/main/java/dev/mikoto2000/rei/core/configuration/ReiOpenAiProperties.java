package dev.mikoto2000.rei.core.configuration;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rei.openai")
public class ReiOpenAiProperties {

  private List<String> baseUrls = new ArrayList<>();
  private List<ServerProperties> servers = new ArrayList<>();

  public List<String> getBaseUrls() {
    return baseUrls;
  }

  public void setBaseUrls(List<String> baseUrls) {
    this.baseUrls = baseUrls;
  }

  public List<ServerProperties> getServers() {
    return servers;
  }

  public void setServers(List<ServerProperties> servers) {
    this.servers = servers;
  }

  public static class ServerProperties {
    private String baseUrl;
    private String chatModel;
    private String embeddingModel;

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getChatModel() {
      return chatModel;
    }

    public void setChatModel(String chatModel) {
      this.chatModel = chatModel;
    }

    public String getEmbeddingModel() {
      return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
      this.embeddingModel = embeddingModel;
    }
  }
}
