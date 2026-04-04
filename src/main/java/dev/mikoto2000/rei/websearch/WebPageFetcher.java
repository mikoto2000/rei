package dev.mikoto2000.rei.websearch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.stereotype.Component;

@Component
public class WebPageFetcher {

  private final WebSearchProperties properties;
  private final WebPageExtractor extractor;
  private final HttpClient httpClient = HttpClient.newHttpClient();

  public WebPageFetcher(WebSearchProperties properties, WebPageExtractor extractor) {
    this.properties = properties;
    this.extractor = extractor;
  }

  public WebSearchPage fetch(WebSearchResult result) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(result.url()))
        .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
        .header("Accept", "text/html,application/xhtml+xml")
        .GET()
        .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() >= 400) {
      throw new IllegalStateException("Web page fetch failed with status " + response.statusCode() + ": " + result.url());
    }
    return extractor.extract(result, response.body());
  }
}
