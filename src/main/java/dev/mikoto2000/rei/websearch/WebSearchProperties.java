package dev.mikoto2000.rei.websearch;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "rei.web-search")
public class WebSearchProperties {

  private boolean enabled = false;

  private int timeoutSeconds = 10;

  private int maxResults = 5;

  private List<ProviderProperties> providers = defaultProviders();

  @Getter
  @Setter
  public static class ProviderProperties {

    private String name;

    private String baseUrl;

    private String apiKey = "";
  }

  private static List<ProviderProperties> defaultProviders() {
    ProviderProperties duckduckgo = new ProviderProperties();
    duckduckgo.setName("duckduckgo");
    duckduckgo.setBaseUrl("https://html.duckduckgo.com/html/");

    return new ArrayList<>(List.of(duckduckgo));
  }
}
