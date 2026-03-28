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
    properties.setApiKey("test-key");
    properties.setMaxResults(5);
    properties.setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/res/v1/web/search");
    WebSearchService service = new WebSearchService(properties, new JsonMapper());

    List<WebSearchResult> results = service.search("spring ai", 2);

    assertEquals("q=spring+ai&count=2", observedQuery.get());
    assertEquals("test-key", observedHeader.get());
    assertEquals(2, results.size());
    assertEquals("Spring AI", results.getFirst().title());
    assertEquals("https://example.com/spring-ai", results.getFirst().url());
  }

  @Test
  void searchRequiresApiKey() {
    WebSearchProperties properties = new WebSearchProperties();
    properties.setEnabled(true);
    WebSearchService service = new WebSearchService(properties, new JsonMapper());

    IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.search("spring ai", 3));

    assertTrue(error.getMessage().contains("api-key"));
  }

  @Test
  void searchRequiresFeatureFlag() {
    WebSearchProperties properties = new WebSearchProperties();
    properties.setApiKey("test-key");
    WebSearchService service = new WebSearchService(properties, new JsonMapper());

    IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.search("spring ai", 3));

    assertTrue(error.getMessage().contains("disabled"));
  }

  @Test
  void parseResultsReturnsEmptyListWhenWebResultsAreMissing() throws IOException {
    WebSearchProperties properties = new WebSearchProperties();
    WebSearchService service = new WebSearchService(properties, new JsonMapper());

    List<WebSearchResult> results = service.parseResults("{}", 3);

    assertTrue(results.isEmpty());
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
}
