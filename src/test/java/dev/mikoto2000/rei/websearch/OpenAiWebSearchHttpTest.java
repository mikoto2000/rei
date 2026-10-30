package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.sun.net.httpserver.HttpServer;
import dev.mikoto2000.rei.http.HttpFetchException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OpenAiWebSearchHttpTest {
  private WebSearchService service(int port, int timeout) {
    var p = new WebSearchProperties.ProviderProperties();
    p.setName("openai");
    p.setBaseUrl("http://localhost:" + port + "/v1/responses");
    p.setApiKey("test-only-key");
    p.setTimeoutSeconds(timeout);
    var properties = new WebSearchProperties();
    properties.setEnabled(true);
    properties.setProviders(List.of(p));
    return new WebSearchService(properties, new JsonMapper());
  }
  @Test void sendsRealPostAndDoesNotFollowCredentialedRedirects() throws Exception {
    var observed = new AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/v1/responses", exchange -> {
      observed.set(exchange.getRequestMethod() + " " + exchange.getRequestHeaders().getFirst("Authorization") + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      exchange.getResponseHeaders().add("Location", "/elsewhere");
      exchange.sendResponseHeaders(307, -1);
      exchange.close();
    });
    server.start();
    try {
      var failure = assertThrows(IllegalStateException.class, () -> service(server.getAddress().getPort(), 5).search("spring", 1));
      assertTrue(failure.getMessage().contains("HTTP_307"));
      assertTrue(observed.get().startsWith("POST Bearer test-only-key "));
      assertTrue(observed.get().contains("web_search_call.action.sources"));
    } finally { server.stop(0); }
  }
  @Test void connectionFailureUsesExistingSafeClassification() throws Exception {
    int port;
    try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
    int closedPort = port;
    var failure = assertThrows(HttpFetchException.class, () -> service(closedPort, 2).search("spring", 1));
    assertEquals(HttpFetchException.Code.NETWORK_ERROR, failure.code());
  }
  @Test void timeoutUsesExistingDeadlineAndIsNotEmptySuccess() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/v1/responses", exchange -> {});
    server.start();
    try {
      var failure = assertThrows(HttpFetchException.class, () -> service(server.getAddress().getPort(), 1).search("spring", 1));
      assertTrue(List.of(HttpFetchException.Code.READ_TIMEOUT, HttpFetchException.Code.TOTAL_TIMEOUT).contains(failure.code()));
    } finally { server.stop(0); }
  }
}
