package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.mikoto2000.rei.websearch.WebSearchProperties.ProviderProperties;
import tools.jackson.databind.json.JsonMapper;

class WebSearchServiceTest {

  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void searchCallsBraveCompatibleEndpointAndParsesResults() throws Exception {
    AtomicReference<String> observedQuery = new AtomicReference<>();
    AtomicReference<String> observedHeader = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/res/v1/web/search", exchange -> respondWithSearchResults(exchange, observedQuery, observedHeader));
    server.start();

    WebSearchProperties properties = new WebSearchProperties();
    properties.setEnabled(true);
    properties.setProviders(List.of(provider(
        "brave",
        "http://localhost:" + server.getAddress().getPort() + "/res/v1/web/search",
        "test-key")));
    WebSearchService service = new WebSearchService(properties, new JsonMapper());

    List<WebSearchResult> results = service.search("spring ai", 2);

    assertEquals("q=spring+ai&count=2", observedQuery.get());
    assertEquals("test-key", observedHeader.get());
    assertEquals(2, results.size());
    assertEquals("Spring AI", results.getFirst().title());
    assertEquals("https://example.com/spring-ai", results.getFirst().url());
  }

  @Test
  void searchCallsDuckDuckGoCompatibleEndpointAndParsesResults() throws Exception {
    AtomicReference<String> observedQuery = new AtomicReference<>();
    AtomicReference<String> observedHeader = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/html/", exchange -> respondWithDuckDuckGoResults(exchange, observedQuery, observedHeader));
    server.start();

    WebSearchProperties properties = new WebSearchProperties();
    properties.setEnabled(true);
    properties.setProviders(List.of(provider(
        "duckduckgo",
        "http://localhost:" + server.getAddress().getPort() + "/html/",
        "")));
    WebSearchService service = new WebSearchService(properties, new JsonMapper());

    List<WebSearchResult> results = service.search("spring ai", 2);

    assertEquals("q=spring+ai", observedQuery.get());
    assertEquals("Rei/0.0.1", observedHeader.get());
    assertEquals(2, results.size());
    assertEquals("Spring AI", results.getFirst().title());
    assertEquals("https://example.com/spring-ai", results.getFirst().url());
    assertEquals("https://example.com/duck", results.get(1).url());
  }

  @Test
  void searchCombinesMultipleProvidersInConfiguredOrder() throws Exception {
    AtomicReference<String> observedDuckQuery = new AtomicReference<>();
    AtomicReference<String> observedBraveQuery = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/duck", exchange -> respondWithDuckDuckGoResults(exchange, observedDuckQuery, new AtomicReference<>()));
    server.createContext("/brave", exchange -> respondWithSearchResults(exchange, observedBraveQuery, new AtomicReference<>()));
    server.start();

    WebSearchProperties properties = new WebSearchProperties();
    properties.setEnabled(true);
    properties.setProviders(List.of(
        provider("duckduckgo", "http://localhost:" + server.getAddress().getPort() + "/duck", ""),
        provider("brave", "http://localhost:" + server.getAddress().getPort() + "/brave", "test-key")));
    WebSearchService service = new WebSearchService(properties, new JsonMapper());

    List<WebSearchResult> results = service.search("spring ai", 2);

    assertEquals("q=spring+ai", observedDuckQuery.get());
    assertEquals("q=spring+ai&count=2", observedBraveQuery.get());
    assertEquals(3, results.size());
    assertEquals(List.of(
        "https://example.com/spring-ai",
        "https://example.com/duck",
        "https://example.com/brave-search"), results.stream().map(WebSearchResult::url).toList());
  }

  @Test
  void searchCollectsUpToLimitResultsFromEveryProvider() throws Exception {
    AtomicReference<String> observedDuckQuery = new AtomicReference<>();
    AtomicReference<String> observedBraveQuery = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/duck", exchange -> respondWithDuckDuckGoResults(exchange, observedDuckQuery, new AtomicReference<>()));
    server.createContext("/brave", exchange -> respondWithBraveUniqueResults(exchange, observedBraveQuery, new AtomicReference<>()));
    server.start();

    WebSearchProperties properties = new WebSearchProperties();
    properties.setEnabled(true);
    properties.setProviders(List.of(
        provider("duckduckgo", "http://localhost:" + server.getAddress().getPort() + "/duck", ""),
        provider("brave", "http://localhost:" + server.getAddress().getPort() + "/brave", "test-key")));
    WebSearchService service = new WebSearchService(properties, new JsonMapper());

    List<WebSearchResult> results = service.search("spring ai", 2);

    assertEquals("q=spring+ai", observedDuckQuery.get());
    assertEquals("q=spring+ai&count=2", observedBraveQuery.get());
    assertEquals(4, results.size());
    assertEquals(List.of(
        "https://example.com/spring-ai",
        "https://example.com/duck",
        "https://example.com/brave-one",
        "https://example.com/brave-two"), results.stream().map(WebSearchResult::url).toList());
  }

  @Test
  void searchFallsBackToLaterProviderWhenEarlierOneFails() throws Exception {
    AtomicReference<String> observedBraveQuery = new AtomicReference<>();
    AtomicReference<String> observedBraveHeader = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/brave", exchange -> respondWithSearchResults(exchange, observedBraveQuery, observedBraveHeader));
    server.start();

    WebSearchProperties properties = new WebSearchProperties();
    properties.setEnabled(true);
    properties.setProviders(List.of(
        provider("unsupported", "http://localhost/unused", ""),
        provider("brave", "http://localhost:" + server.getAddress().getPort() + "/brave", "test-key")));
    WebSearchService service = new WebSearchService(properties, new JsonMapper());

    List<WebSearchResult> results = service.search("spring ai", 2);

    assertEquals("q=spring+ai&count=2", observedBraveQuery.get());
    assertEquals("test-key", observedBraveHeader.get());
    assertEquals(2, results.size());
  }

  @Test
  void configuredProvidersRequiresAtLeastOneValidProvider() {
    WebSearchProperties properties = new WebSearchProperties();
    properties.setProviders(List.of(provider("", "", "")));
    WebSearchService service = new WebSearchService(properties, new JsonMapper());

    IllegalStateException error = assertThrows(IllegalStateException.class, service::configuredProviders);

    assertTrue(error.getMessage().startsWith("No web search providers are configured."));
  }

  @Test
  void searchRequiresApiKeyForBrave() {
    WebSearchProperties properties = new WebSearchProperties();
    properties.setEnabled(true);
    properties.setProviders(List.of(provider("brave", "https://api.search.brave.com/res/v1/web/search", "")));
    WebSearchService service = new WebSearchService(properties, new JsonMapper());

    IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.search("spring ai", 3));

    assertEquals("Web search API key is not configured for provider brave.", error.getMessage());
  }

  @Test
  void searchRequiresFeatureFlag() {
    WebSearchProperties properties = new WebSearchProperties();
    properties.setProviders(List.of(provider("brave", "https://api.search.brave.com/res/v1/web/search", "test-key")));
    WebSearchService service = new WebSearchService(properties, new JsonMapper());

    IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.search("spring ai", 3));

    assertEquals("Web search is disabled. Set REI_WEB_SEARCH_ENABLED=true to enable it.", error.getMessage());
  }

  @Test
  void parseBraveResultsReturnsEmptyListWhenWebResultsAreMissing() throws IOException {
    WebSearchProperties properties = new WebSearchProperties();
    WebSearchService service = new WebSearchService(properties, new JsonMapper());

    List<WebSearchResult> results = service.parseBraveResults("{}", 3);

    assertTrue(results.isEmpty());
  }

  @Test
  void parseDuckDuckGoResultsReturnsEmptyListWhenResultsAreMissing() {
    WebSearchProperties properties = new WebSearchProperties();
    WebSearchService service = new WebSearchService(properties, new JsonMapper());

    List<WebSearchResult> results = service.parseDuckDuckGoResults("<html></html>", 3);

    assertTrue(results.isEmpty());
  }

  @Test
  void searchReturnsEmptyListWhenProvidersAreConfiguredButNoResultsAreFound() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/html/", exchange -> {
      byte[] body = "<html><body></body></html>".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream outputStream = exchange.getResponseBody()) {
        outputStream.write(body);
      }
    });
    server.start();

    WebSearchProperties properties = new WebSearchProperties();
    properties.setEnabled(true);
    properties.setProviders(List.of(provider(
        "duckduckgo",
        "http://localhost:" + server.getAddress().getPort() + "/html/",
        "")));
    WebSearchService service = new WebSearchService(properties, new JsonMapper());

    List<WebSearchResult> results = service.search("spring ai", 2);

    assertTrue(results.isEmpty());
  }

  private ProviderProperties provider(String name, String baseUrl, String apiKey) {
    ProviderProperties provider = new ProviderProperties();
    provider.setName(name);
    provider.setBaseUrl(baseUrl);
    provider.setApiKey(apiKey);
    return provider;
  }

  private void respondWithSearchResults(HttpExchange exchange, AtomicReference<String> observedQuery,
      AtomicReference<String> observedHeader) throws IOException {
    observedQuery.set(exchange.getRequestURI().getRawQuery());
    observedHeader.set(exchange.getRequestHeaders().getFirst("X-Subscription-Token"));
    byte[] body = """
        {
          "web": {
            "results": [
              {
                "title": "Spring AI",
                "url": "https://example.com/spring-ai",
                "description": "Spring AI official documentation",
                "age": "2026-03-01"
              },
              {
                "title": "Brave Search API",
                "url": "https://example.com/brave-search",
                "description": "Brave Search API docs",
                "age": "2026-03-02"
              }
            ]
          }
        }
        """.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream outputStream = exchange.getResponseBody()) {
      outputStream.write(body);
    }
  }

  private void respondWithDuckDuckGoResults(HttpExchange exchange, AtomicReference<String> observedQuery,
      AtomicReference<String> observedHeader) throws IOException {
    observedQuery.set(exchange.getRequestURI().getRawQuery());
    observedHeader.set(exchange.getRequestHeaders().getFirst("User-Agent"));
    byte[] body = """
        <html>
          <body>
            <div class="result">
              <a class="result__a" href="https://example.com/spring-ai">Spring AI</a>
              <a class="result__snippet">Spring AI official documentation</a>
            </div>
            <div class="result">
              <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fduck">Duck Result</a>
              <div class="result__snippet">DuckDuckGo redirect result</div>
            </div>
          </body>
        </html>
        """.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream outputStream = exchange.getResponseBody()) {
      outputStream.write(body);
    }
  }

  private void respondWithBraveUniqueResults(HttpExchange exchange, AtomicReference<String> observedQuery,
      AtomicReference<String> observedHeader) throws IOException {
    observedQuery.set(exchange.getRequestURI().getRawQuery());
    observedHeader.set(exchange.getRequestHeaders().getFirst("X-Subscription-Token"));
    byte[] body = """
        {
          "web": {
            "results": [
              {
                "title": "Brave One",
                "url": "https://example.com/brave-one",
                "description": "Brave result one",
                "age": "2026-03-02"
              },
              {
                "title": "Brave Two",
                "url": "https://example.com/brave-two",
                "description": "Brave result two",
                "age": "2026-03-03"
              }
            ]
          }
        }
        """.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream outputStream = exchange.getResponseBody()) {
      outputStream.write(body);
    }
  }
}
