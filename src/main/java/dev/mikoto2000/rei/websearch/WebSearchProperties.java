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
  private int connectTimeoutSeconds = 5;
  private int readTimeoutSeconds = 10;
  private int maxWireBytes = 2 * 1024 * 1024;
  private int maxDecodedBytes = 4 * 1024 * 1024;
  private int maxRedirects = 5;

  public dev.mikoto2000.rei.http.HttpFetchPolicy fetchPolicy() {
    return fetchPolicy(null);
  }
  public dev.mikoto2000.rei.http.HttpFetchPolicy fetchPolicy(ProviderProperties provider) {
    return new dev.mikoto2000.rei.http.HttpFetchPolicy(maxWireBytes, maxDecodedBytes,
        java.time.Duration.ofSeconds(connectTimeoutSeconds), java.time.Duration.ofSeconds(readTimeoutSeconds),
        java.time.Duration.ofSeconds(timeoutSeconds), maxRedirects, null,
        provider == null ? null : java.net.URI.create(provider.getBaseUrl()),
        provider != null && provider.isAllowPrivateNetwork());
  }

  private int maxResults = 5;

  private List<ProviderProperties> providers = defaultProviders();

  @Getter
  @Setter
  public static class ProviderProperties {

    private String name;

    private String baseUrl;

    private String apiKey = "";
    private boolean allowPrivateNetwork = false;
  }

  private static List<ProviderProperties> defaultProviders() {
    ProviderProperties duckduckgo = new ProviderProperties();
    duckduckgo.setName("duckduckgo");
    duckduckgo.setBaseUrl("https://html.duckduckgo.com/html/");

    return new ArrayList<>(List.of(duckduckgo));
  }
}
