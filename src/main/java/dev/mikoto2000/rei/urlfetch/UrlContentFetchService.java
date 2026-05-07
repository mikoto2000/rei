package dev.mikoto2000.rei.urlfetch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UrlContentFetchService {

  private static final int DEFAULT_TIMEOUT_SECONDS = 30;

  private final UrlValidator urlValidator;
  private final HttpClient httpClient;

  @Autowired
  public UrlContentFetchService(UrlValidator urlValidator) {
    this(urlValidator, HttpClient.newHttpClient());
  }

  UrlContentFetchService(UrlValidator urlValidator, HttpClient httpClient) {
    this.urlValidator = urlValidator;
    this.httpClient = httpClient;
  }

  public UrlContentFetchResult fetch(String url) {
    UrlContentFetchResult validation = urlValidator.validate(url);
    if (!validation.success()) {
      return validation;
    }

    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .timeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
          .header("Accept", "text/plain,text/html,application/xhtml+xml,application/json")
          .GET()
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() >= 400) {
        return UrlContentFetchResult.failure(
            "HTTP_ERROR",
            "HTTP request failed with status: " + response.statusCode(),
            response.statusCode());
      }
      if (response.body() == null) {
        return UrlContentFetchResult.failure("EXTRACTION_ERROR", "Response body is empty");
      }
      return UrlContentFetchResult.success(response.body());
    } catch (IOException e) {
      return UrlContentFetchResult.failure("NETWORK_ERROR", "Network error: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return UrlContentFetchResult.failure("NETWORK_ERROR", "Request interrupted");
    } catch (RuntimeException e) {
      return UrlContentFetchResult.failure("EXTRACTION_ERROR", "Failed to fetch URL content: " + e.getMessage());
    }
  }
}
