package dev.mikoto2000.rei.websearch;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "rei.web-search")
public class WebSearchProperties {

  private boolean enabled = false;

  private String baseUrl = "https://api.search.brave.com/res/v1/web/search";

  private String apiKey = "";

  private int timeoutSeconds = 10;

  private int maxResults = 5;
}
