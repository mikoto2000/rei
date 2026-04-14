package dev.mikoto2000.rei.websearch;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
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

    int requestedLimit = limit == null ? properties.getMaxResults() : limit;
    int clampedLimit = Math.max(1, Math.min(requestedLimit, properties.getMaxResults()));
    return switch (normalizedProvider()) {
      case "duckduckgo" -> searchDuckDuckGo(query, clampedLimit);
      case "brave" -> searchBrave(query, clampedLimit);
      default -> throw new IllegalStateException("Unsupported web search provider: " + properties.getProvider());
    };
  }

  private List<WebSearchResult> searchBrave(String query, int limit) throws IOException, InterruptedException {
    if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
      throw new IllegalStateException("Web search API key is not configured. Set REI_WEB_SEARCH_API_KEY.");
    }

    String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
    URI uri = URI.create(resolveBaseUrl("https://api.search.brave.com/res/v1/web/search")
        + "?q=" + encodedQuery + "&count=" + limit);

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

    return parseBraveResults(response.body(), limit);
  }

  private List<WebSearchResult> searchDuckDuckGo(String query, int limit) throws IOException, InterruptedException {
    String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
    URI uri = URI.create(resolveBaseUrl("https://html.duckduckgo.com/html/") + "?q=" + encodedQuery);

    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
        .header("Accept", "text/html")
        .header("User-Agent", "Rei/0.0.1")
        .GET()
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() >= 400) {
      throw new IllegalStateException("Web search failed with status " + response.statusCode() + ": " + response.body());
    }

    return parseDuckDuckGoResults(response.body(), limit);
  }

  String normalizedProvider() {
    return properties.getProvider() == null || properties.getProvider().isBlank()
        ? "duckduckgo"
        : properties.getProvider().trim().toLowerCase();
  }

  String resolveBaseUrl(String defaultUrl) {
    return properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
        ? defaultUrl
        : properties.getBaseUrl();
  }

  List<WebSearchResult> parseBraveResults(String responseBody, int limit) throws IOException {
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

  List<WebSearchResult> parseDuckDuckGoResults(String responseBody, int limit) {
    Document document = Jsoup.parse(responseBody);
    List<WebSearchResult> parsed = new ArrayList<>();

    for (Element result : document.select(".result")) {
      if (parsed.size() >= limit) {
        break;
      }

      Element link = result.selectFirst("a.result__a");
      if (link == null) {
        continue;
      }

      String url = extractDuckDuckGoTargetUrl(link.attr("href"));
      if (url.isBlank()) {
        continue;
      }

      Element snippet = result.selectFirst(".result__snippet");
      parsed.add(new WebSearchResult(
          link.text(),
          url,
          snippet == null ? "" : snippet.text(),
          null));
    }

    return parsed;
  }

  String extractDuckDuckGoTargetUrl(String href) {
    if (href == null || href.isBlank()) {
      return "";
    }
    if (!href.startsWith("//duckduckgo.com/l/") && !href.startsWith("https://duckduckgo.com/l/")
        && !href.startsWith("http://duckduckgo.com/l/")) {
      return href;
    }

    URI uri = URI.create(href.startsWith("//") ? "https:" + href : href);
    String query = uri.getRawQuery();
    if (query == null || query.isBlank()) {
      return href;
    }

    for (String pair : query.split("&")) {
      int separator = pair.indexOf('=');
      if (separator < 0) {
        continue;
      }
      if ("uddg".equals(pair.substring(0, separator))) {
        return URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
      }
    }
    return href;
  }
}
