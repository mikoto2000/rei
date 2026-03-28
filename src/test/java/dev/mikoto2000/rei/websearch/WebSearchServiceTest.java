package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
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
  void searchCallsOpenAiResponsesEndpointAndParsesSummaryAndSources() throws Exception {
    AtomicReference<String> observedBody = new AtomicReference<>();
    AtomicReference<String> observedHeader = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/v1/responses", exchange -> respondWithSearchResults(exchange, observedBody, observedHeader));
    server.start();

    WebSearchProperties properties = new WebSearchProperties();
    properties.setEnabled(true);
    properties.setApiKey("test-key");
    properties.setModel("gpt-5");
    properties.setMaxResults(5);
    properties.setMaxOutputTokens(400);
    properties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
    WebSearchService service = new WebSearchService(properties, new JsonMapper());

    WebSearchResponse response = service.search("spring ai", 2);

    JsonNode requestJson = new JsonMapper().readTree(observedBody.get());
    assertEquals("Bearer test-key", observedHeader.get());
    assertEquals("gpt-5", requestJson.path("model").asText());
    assertEquals(400, requestJson.path("max_output_tokens").asInt());
    assertEquals("web_search_call.action.sources", requestJson.path("include").get(0).asText());
    assertEquals("web_search", requestJson.path("tools").get(0).path("type").asText());
    assertTrue(requestJson.path("input").asText().contains("spring ai"));
    assertEquals("Spring AI is a framework for building AI apps with Spring.", response.summary());
    assertEquals(2, response.sources().size());
    assertEquals("Spring AI", response.sources().getFirst().title());
    assertEquals("https://example.com/spring-ai", response.sources().getFirst().url());
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
  void parseResponseReturnsEmptySourcesWhenWebResultsAreMissing() throws IOException {
    WebSearchProperties properties = new WebSearchProperties();
    WebSearchService service = new WebSearchService(properties, new JsonMapper());

    WebSearchResponse response = service.parseResponse("{}", 3);

    assertEquals("", response.summary());
    assertTrue(response.sources().isEmpty());
  }

  @Test
  void responsesUriAcceptsBaseUrlOrFullEndpoint() {
    assertEquals(URI.create("https://api.openai.com/v1/responses"), WebSearchService.responsesUri("https://api.openai.com"));
    assertEquals(URI.create("https://api.openai.com/v1/responses"), WebSearchService.responsesUri("https://api.openai.com/v1"));
    assertEquals(URI.create("https://api.openai.com/v1/responses"),
        WebSearchService.responsesUri("https://api.openai.com/v1/responses"));
  }

  private void respondWithSearchResults(HttpExchange exchange, AtomicReference<String> observedBody,
      AtomicReference<String> observedHeader) throws IOException {
    observedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    observedHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
    byte[] body = """
        {
          "output": [
            {
              "type": "web_search_call",
              "action": {
                "sources": [
                  {
                    "title": "Spring AI",
                    "url": "https://example.com/spring-ai",
                    "snippet": "Spring AI official documentation",
                    "published_at": "2026-03-01"
                  },
                  {
                    "title": "OpenAI Responses API",
                    "url": "https://example.com/openai-responses",
                    "snippet": "OpenAI Responses API docs",
                    "published_at": null
                  }
                ]
              }
            },
            {
              "type": "message",
              "role": "assistant",
              "content": [
                {
                  "type": "output_text",
                  "text": "Spring AI is a framework for building AI apps with Spring."
                }
              ]
            }
          ]
        }
        """.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream outputStream = exchange.getResponseBody()) {
      outputStream.write(body);
    }
  }
}
