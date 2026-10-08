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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.mikoto2000.rei.websearch.WebSearchProperties.ProviderProperties;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class WebSearchService {

  private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

  private final WebSearchProperties properties;

  private final JsonMapper objectMapper;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  public List<WebSearchResult> search(String query, Integer limit) throws IOException, InterruptedException {
    long started = System.nanoTime();
    try { return searchObserved(query, limit); }
    finally { WebSearchMetrics.OBSERVED.duration("search", System.nanoTime() - started); }
  }

  private List<WebSearchResult> searchObserved(String query, Integer limit) throws IOException, InterruptedException {
    if (!properties.isEnabled()) {
      throw new IllegalStateException("Web search is disabled. Set REI_WEB_SEARCH_ENABLED=true to enable it.");
    }

    int requestedLimit = limit == null ? properties.getMaxResults() : limit;
    int clampedLimit = Math.max(1, Math.min(requestedLimit, properties.getMaxResults()));
    List<ProviderProperties> providers = configuredProviders();
    Map<String, WebSearchResult> resultsByUrl = new LinkedHashMap<>();
    Exception firstError = null;

    for (ProviderProperties provider : providers) {
      try {
        for (WebSearchResult result : searchWithProvider(provider, query, clampedLimit)) {
          WebSearchMetrics.OBSERVED.add("results", 1);
          if (resultsByUrl.putIfAbsent(result.url(), result) != null)
            WebSearchMetrics.OBSERVED.add("duplicate_urls", 1);
        }
      } catch (IOException | InterruptedException | RuntimeException e) {
        if (firstError == null) {
          firstError = e;
        }
      }
    }

    if (!resultsByUrl.isEmpty()) {
      return new ArrayList<>(resultsByUrl.values());
    }
    if (firstError instanceof IOException ioException) {
      throw ioException;
    }
    if (firstError instanceof InterruptedException interruptedException) {
      throw interruptedException;
    }
    if (firstError instanceof RuntimeException runtimeException) {
      throw runtimeException;
    }
    return List.of();
  }

  List<ProviderProperties> configuredProviders() {
    if (properties.getProviders() == null || properties.getProviders().isEmpty()) {
      String detail = "enabled=%s, providersCount=0".formatted(properties.isEnabled());
      log.warn("Web search providers were empty at execution time: {}", detail);
      throw new IllegalStateException("No web search providers are configured. " + detail);
    }

    List<ProviderProperties> providers = new ArrayList<>();
    for (ProviderProperties provider : properties.getProviders()) {
      if (provider == null || provider.getName() == null || provider.getName().isBlank()) {
        continue;
      }
      providers.add(provider);
    }
    if (providers.isEmpty()) {
      String detail = "enabled=%s, providersCount=%s".formatted(properties.isEnabled(), properties.getProviders().size());
      log.warn("Web search providers were present but all invalid at execution time: {}", detail);
      throw new IllegalStateException("No web search providers are configured. " + detail);
    }
    return providers;
  }

  private List<WebSearchResult> searchWithProvider(ProviderProperties provider, String query, int limit)
      throws IOException, InterruptedException {
    String providerName = provider.getName().trim().toLowerCase();
    return switch (providerName) {
      case "duckduckgo" -> searchDuckDuckGo(provider, query, limit);
      case "brave" -> searchBrave(provider, query, limit);
      default -> throw new IllegalStateException("Unsupported web search provider: " + provider.getName());
    };
  }

  private List<WebSearchResult> searchBrave(ProviderProperties provider, String query, int limit)
      throws IOException, InterruptedException {
    if (provider.getApiKey() == null || provider.getApiKey().isBlank()) {
      throw new IllegalStateException("Web search API key is not configured for provider brave.");
    }

    String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
    URI uri = URI.create(provider.getBaseUrl() + "?q=" + encodedQuery + "&count=" + limit);

    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
        .header("Accept", "application/json")
        .header("X-Subscription-Token", provider.getApiKey())
        .GET()
        .build();

    HttpResponse<byte[]> response = send(request, "brave");
    if (response.statusCode() >= 400) {
      throw new IllegalStateException("Web search failed with status " + response.statusCode());
    }

    return parseBraveResults(new String(response.body(), StandardCharsets.UTF_8), limit);
  }

  private List<WebSearchResult> searchDuckDuckGo(ProviderProperties provider, String query, int limit)
      throws IOException, InterruptedException {
    String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
    URI uri = URI.create(provider.getBaseUrl() + "?q=" + encodedQuery);

    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
        .header("Accept", "text/html")
        .header("User-Agent", "Rei/0.0.1")
        .GET()
        .build();

    HttpResponse<byte[]> response = send(request, "duckduckgo");
    if (response.statusCode() >= 400) {
      throw new IllegalStateException("Web search failed with status " + response.statusCode());
    }

    return parseDuckDuckGoResults(new String(response.body(), StandardCharsets.UTF_8), limit);
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

  private HttpResponse<byte[]> send(HttpRequest request, String provider) throws IOException, InterruptedException {
    WebSearchMetrics.OBSERVED.provider(provider);
    WebSearchMetrics.OBSERVED.add("search_api_calls", 1);
    WebSearchMetrics.OBSERVED.add("http_requests", 1);
    try {
      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      WebSearchMetrics.OBSERVED.status(response.statusCode());
      WebSearchMetrics.OBSERVED.add("received_bytes", response.body().length);
      return response;
    } catch (java.net.http.HttpTimeoutException e) {
      WebSearchMetrics.OBSERVED.add("timeouts", 1); throw e;
    } catch (InterruptedException e) {
      WebSearchMetrics.OBSERVED.add("cancellations", 1); throw e;
    }
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
