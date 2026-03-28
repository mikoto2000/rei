package dev.mikoto2000.rei.websearch;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "rei.web-search")
public class WebSearchProperties {

  private boolean enabled = false;

  private String baseUrl = "https://api.openai.com";

  private String apiKey = "";

  private String model = "gpt-5";

  private int timeoutSeconds = 30;

  private int maxResults = 5;

  private int maxOutputTokens = 400;
}
