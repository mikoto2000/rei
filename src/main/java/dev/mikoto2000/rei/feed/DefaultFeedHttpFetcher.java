package dev.mikoto2000.rei.feed;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.stereotype.Component;

@Component
public class DefaultFeedHttpFetcher implements FeedHttpFetcher {

  private final HttpClient httpClient = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  @Override
  public FeedHttpResponse fetch(URI uri) {
    try {
      HttpRequest request = HttpRequest.newBuilder(uri)
          .timeout(Duration.ofSeconds(30))
          .header("User-Agent", "rei-feed-fetcher/1.0")
          .GET()
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      return new FeedHttpResponse(response.statusCode(), response.body());
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new FeedFetchException("フィードの取得に失敗しました", null, e);
    }
  }
}
