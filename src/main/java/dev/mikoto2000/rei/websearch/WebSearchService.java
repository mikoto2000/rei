package dev.mikoto2000.rei.websearch;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class WebSearchService {

  private final WebSearchProperties properties;

  private final JsonMapper objectMapper;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  public List<WebSearchResult> search(String query, Integer limit) throws IOException, InterruptedException {
    if (!properties.isEnabled()) {
      throw new IllegalStateException("Web search is disabled. Set REI_WEB_SEARCH_ENABLED=true to enable it.");
    }
    if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
      throw new IllegalStateException("Web search API key is not configured. Set REI_WEB_SEARCH_API_KEY.");
    }

    int requestedLimit = limit == null ? properties.getMaxResults() : limit;
    int clampedLimit = Math.max(1, Math.min(requestedLimit, properties.getMaxResults()));
    String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
    URI uri = URI.create(properties.getBaseUrl() + "?q=" + encodedQuery + "&count=" + clampedLimit);

    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
        .header("Accept", "application/json")
        .header("X-Subscription-Token", properties.getApiKey())
        .GET()
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() >= 400) {
      throw new IllegalStateException("Web search failed with status " + response.statusCode() + ": " + response.body());
    }

    return parseResults(response.body(), clampedLimit);
  }

  List<WebSearchResult> parseResults(String responseBody, int limit) throws IOException {
    JsonNode root = objectMapper.readTree(responseBody);
    JsonNode results = root.path("web").path("results");
    List<WebSearchResult> parsed = new ArrayList<>();

    if (!results.isArray()) {
      return parsed;
    }

    for (JsonNode result : results) {
      if (parsed.size() >= limit) {
        break;
      }
      parsed.add(new WebSearchResult(
          result.path("title").asString(""),
          result.path("url").asString(""),
          result.path("description").asString(""),
          result.path("age").asString(null)));
    }

    return parsed;
  }
}
