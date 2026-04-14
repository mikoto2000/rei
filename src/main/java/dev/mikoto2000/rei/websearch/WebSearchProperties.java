package dev.mikoto2000.rei.websearch;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "rei.web-search")
public class WebSearchProperties {

  private boolean enabled = false;

  private String provider = "duckduckgo";

  private String baseUrl = "";

  private String apiKey = "";

  private int timeoutSeconds = 10;

  private int maxResults = 5;
}
