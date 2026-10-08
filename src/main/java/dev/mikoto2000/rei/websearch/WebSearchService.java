package dev.mikoto2000.rei.websearch;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class WebSearchService {

  private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

  private final WebSearchProperties properties;

  private final JsonMapper objectMapper;

  private final dev.mikoto2000.rei.http.SafeHttpFetcher fetcher;
  private final dev.mikoto2000.rei.http.cache.HttpResponseCache cache;

  public WebSearchService(WebSearchProperties properties, JsonMapper objectMapper) {
    this(properties, objectMapper, new dev.mikoto2000.rei.http.SafeHttpFetcher());
  }
  public WebSearchService(WebSearchProperties properties, JsonMapper objectMapper,
      dev.mikoto2000.rei.http.SafeHttpFetcher fetcher) {
    this(properties, objectMapper, fetcher, dev.mikoto2000.rei.http.cache.HttpResponseCache.disabled());
  }
  @org.springframework.beans.factory.annotation.Autowired
  public WebSearchService(WebSearchProperties properties, JsonMapper objectMapper,
      dev.mikoto2000.rei.http.SafeHttpFetcher fetcher, dev.mikoto2000.rei.http.cache.HttpResponseCache cache) {
    this.properties = properties; this.objectMapper = objectMapper; this.fetcher = fetcher; this.cache = cache;
  }

  public List<WebSearchResult> search(String query, Integer limit) throws IOException, InterruptedException {
    long started = System.nanoTime();
    properties.validateSelection();
    try (var refresh = dev.mikoto2000.rei.http.FetchScope.withForceRefresh(query != null && WebSearchSelection.needsFreshness(query));
        var research = WebResearchScope.enter(properties);
        var budget = SearchRequestBudget.enter(properties.getMaxSearchApiCalls())) { return searchObserved(query, limit); }
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
      if (SearchRequestBudget.exhausted()) break;
      dev.mikoto2000.rei.http.FetchScope.current().check();
      try {
        for (WebSearchResult result : searchWithProvider(provider, query, clampedLimit)) {
          WebSearchMetrics.OBSERVED.add("results", 1);
          if (resultsByUrl.containsKey(result.url()))
            WebSearchMetrics.OBSERVED.add("duplicate_urls", 1);
          resultsByUrl.merge(result.url(), result, (existing, replacement) ->
              WebSearchSelection.priority(query, replacement) > WebSearchSelection.priority(query, existing) ? replacement : existing);
        }
        if (WebSearchSelection.sufficient(query, new ArrayList<>(resultsByUrl.values()), clampedLimit)) break;
      } catch (IOException | InterruptedException | RuntimeException e) {
        dev.mikoto2000.rei.http.FetchOperation.propagateControls(e);
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

    var response = send(request, provider, limit);
    if (response.status() >= 400) {
      throw new IllegalStateException("Web search failed with status " + response.status());
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

    var response = send(request, provider, limit);
    if (response.status() >= 400) {
      throw new IllegalStateException("Web search failed with status " + response.status());
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

  private dev.mikoto2000.rei.http.SafeHttpFetcher.Response send(HttpRequest request, ProviderProperties provider, int limit) {
    Map<String, String> headers = new LinkedHashMap<>();
    request.headers().map().forEach((name, values) -> headers.put(name, String.join(",", values)));
    var observed = WebSearchMetrics.OBSERVED.http(provider.getName().trim().toLowerCase(java.util.Locale.ROOT));
    var gate = SearchRequestBudget.captureGate();
    var observer = new dev.mikoto2000.rei.http.HttpFetchObserver() {
      public void request(boolean redirect) { gate.run(); observed.request(redirect); }
      public void status(int status) { observed.status(status); }
      public void bytes(int bytes) { observed.bytes(bytes); }
      public void failure(dev.mikoto2000.rei.http.HttpFetchException.Code code) { observed.failure(code); }
      public void cancellation() { observed.cancellation(); }
    };
    return cache.fetch(new dev.mikoto2000.rei.http.cache.HttpResponseCache.Request(request.uri(), headers,
        properties.fetchPolicy(provider), dev.mikoto2000.rei.http.cache.HttpResponseCache.Namespace.SEARCH,
        dev.mikoto2000.rei.http.FetchScope.forceRefresh(), provider.getName().trim().toLowerCase(java.util.Locale.ROOT) + ":" + limit),
        dev.mikoto2000.rei.http.FetchScope.current(), observer, fetcher);
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
