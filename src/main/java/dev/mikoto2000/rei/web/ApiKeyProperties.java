package dev.mikoto2000.rei.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The key is supplied once by bootstrap, never exposed by toString. */
@ConfigurationProperties("rei")
public final class ApiKeyProperties {
  private final String apiKey;
  public ApiKeyProperties(String apiKey) { this.apiKey = apiKey; }
  public String getApiKey() { return apiKey; }
}
