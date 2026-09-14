package dev.mikoto2000.rei.vectordocument;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rei.rerank")
public record RerankProperties(String baseUrl, String apiKey, String model, String path) {
  public RerankProperties {
    if (path == null || path.isBlank()) path = "/v1/rerank";
  }

  public boolean enabled() {
    return baseUrl != null && !baseUrl.isBlank();
  }
}
