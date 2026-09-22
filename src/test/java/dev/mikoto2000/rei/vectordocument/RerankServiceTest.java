package dev.mikoto2000.rei.vectordocument;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class RerankServiceTest {
  private HttpServer server;
  private String baseUrl;
  private final AtomicReference<String> body = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private String response = """
      {"results":[{"index":0,"relevance_score":0.1},{"index":1,"relevance_score":0.9}]}
      """;
  private int status = 200;

  @BeforeEach void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/custom/rerank", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach void stop() { server.stop(0); }

  private RerankService service() {
    return new RerankService(new RerankProperties(true, baseUrl, "rerank-key", "reranker", "/custom/rerank"),
        RestClient.builder());
  }

  @Test void sendsIndependentCredentialsModelAndFullTextAndReranksBeforeLimit() {
    VectorStore store = mock(VectorStore.class);
    when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
        Document.builder().id("a#0").text("First full document")
            .metadata(Map.of("docId", "a", "source", "a.txt", "chunkIndex", 0)).score(0.9).build(),
        Document.builder().id("b#0").text("Second full document")
            .metadata(Map.of("docId", "b", "source", "b.txt", "chunkIndex", 0)).score(0.2).build()));
    var documents = new VectorDocumentService(store, mock(VectorDocumentRepository.class),
        new VectorDocumentProperties(512, 0));
    documents.setRerankService(service());
    var results = documents.search("question", 1, null, null);
    assertEquals(1, results.size());
    assertEquals("b", results.getFirst().docId());
    var request = JsonMapper.builder().build().readTree(body.get());
    assertEquals("question", request.get("query").asString());
    assertEquals("reranker", request.get("model").asString());
    assertEquals("First full document", request.get("documents").get(0).asString());
    assertEquals("Second full document", request.get("documents").get(1).asString());
    assertEquals("Bearer rerank-key", authorization.get());
  }

  @Test void disabledAndEmptySearchDoNotSendRequests() {
    for (String url : new String[] {null, "", "  "}) {
      var disabled = new RerankService(new RerankProperties(true, url, "", "", null), RestClient.builder());
      assertEquals(List.of("a"), disabled.rerank("q", List.of("a"), s -> s));
    }
    assertTrue(service().rerank("q", List.<String>of(), s -> s).isEmpty());
    assertNull(body.get());
  }

  @Test void failureKeepsOriginalOrder() {
    status = 500;
    assertEquals(List.of("a", "b"), service().rerank("q", List.of("a", "b"), s -> s));
  }

  @Test void malformedIncompleteAndDuplicateResultsKeepOriginalOrder() {
    for (String invalid : List.of("{}", "{\"results\":[]}",
        "{\"results\":[{\"index\":0,\"relevance_score\":1},{\"index\":0,\"relevance_score\":2}]}",
        "{\"results\":[{\"index\":2,\"relevance_score\":1},{\"index\":0,\"relevance_score\":2}]}")) {
      response = invalid;
      assertEquals(List.of("a", "b"), service().rerank("q", List.of("a", "b"), s -> s));
    }
  }

  @Test void configuredEndpointRequiresModel() {
    assertThrows(IllegalArgumentException.class, () -> new RerankService(
        new RerankProperties(true, baseUrl, "", "", null), RestClient.builder()));
  }

  @Test void explicitlyDisabledSkipsRequestsAndModelValidation() {
    for (String model : new String[] {null, "", "reranker"}) {
      var properties = bind(Map.of("rei.rerank.enabled", "false", "rei.rerank.base-url", baseUrl));
      assertFalse(properties.enabled());
      var disabled = new RerankService(new RerankProperties(properties.enabled(), properties.baseUrl(),
          "rerank-key", model, "/custom/rerank"), RestClient.builder());
      var candidates = List.of("a", "b");
      assertSame(candidates, disabled.rerank("q", candidates, s -> s));
    }
    assertNull(body.get());
  }

  @Test void omittedEnabledPreservesExistingConfigurationAndExplicitTrueEnablesReranking() {
    for (String enabled : new String[] {null, "true"}) {
      var values = new java.util.HashMap<String, Object>();
      values.put("rei.rerank.base-url", baseUrl);
      values.put("rei.rerank.model", "reranker");
      values.put("rei.rerank.path", "/custom/rerank");
      if (enabled != null) values.put("rei.rerank.enabled", enabled);
      var properties = bind(values);
      assertTrue(properties.enabled());
      var configured = new RerankService(properties, RestClient.builder());
      assertEquals(List.of("b", "a"), configured.rerank("q", List.of("a", "b"), s -> s));
    }
  }

  private RerankProperties bind(Map<String, ?> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bind("rei.rerank", Bindable.of(RerankProperties.class)).get();
  }
}
