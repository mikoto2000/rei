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
    long started = System.nanoTime();
    try { return fetchObserved(result); }
    catch (IOException | InterruptedException | RuntimeException e) {
      WebSearchMetrics.OBSERVED.add("fetch_failures", 1);
      if (e instanceof java.net.http.HttpTimeoutException) WebSearchMetrics.OBSERVED.add("timeouts", 1);
      if (e instanceof InterruptedException) WebSearchMetrics.OBSERVED.add("cancellations", 1);
      throw e;
    } finally { WebSearchMetrics.OBSERVED.duration("fetch", System.nanoTime() - started); }
  }

  private WebSearchPage fetchObserved(WebSearchResult result) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(result.url()))
        .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
        .header("Accept", "text/html,application/xhtml+xml")
        .GET()
        .build();
    WebSearchMetrics.OBSERVED.add("http_requests", 1);
    HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    WebSearchMetrics.OBSERVED.status(response.statusCode());
    WebSearchMetrics.OBSERVED.add("received_bytes", response.body().length);
    if (response.statusCode() >= 400) {
      throw new IllegalStateException("Web page fetch failed with status " + response.statusCode() + ": " + result.url());
    }
    WebSearchMetrics.OBSERVED.add("fetch_successes", 1);
    return extractor.extract(result, new String(response.body(), StandardCharsets.UTF_8));
  }
}
