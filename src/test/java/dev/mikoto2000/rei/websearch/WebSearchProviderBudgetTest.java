package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

class WebSearchProviderBudgetTest {
  HttpServer server; WebSearchProperties properties; AtomicInteger first, second;
  @BeforeEach void start() throws Exception {
    first = new AtomicInteger(); second = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.start();
    properties = new WebSearchProperties(); properties.setEnabled(true);
  }
  @AfterEach void close() { server.stop(0); }
  WebSearchProperties.ProviderProperties provider(String path) {
    var result = new WebSearchProperties.ProviderProperties(); result.setName("duckduckgo");
    result.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + path); return result;
  }
  void html(String path, AtomicInteger calls, String title) {
    server.createContext(path, exchange -> {
      calls.incrementAndGet();
      byte[] body = ("<div class=result><a class=result__a href=https://docs.oracle.com/java>" + title + "</a><span class=result__snippet>" + title + "</span></div>").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (var output = exchange.getResponseBody()) { output.write(body); }
    });
  }
  @Test void sufficientFirstProviderAvoidsTheSecondPhysicalCall() throws Exception {
    html("/first", first, "Java streams"); html("/second", second, "Java streams");
    properties.setProviders(List.of(provider("/first"), provider("/second")));
    assertEquals(1, new WebSearchService(properties, new JsonMapper()).search("Java streams", 1).size());
    assertEquals(1, first.get()); assertEquals(0, second.get());
  }
  @Test void wrongVersionDoesNotSuppressLaterProvider() throws Exception {
    html("/first", first, "Java 24 reference"); html("/second", second, "Java 25 reference");
    properties.setProviders(List.of(provider("/first"), provider("/second")));
    var results = new WebSearchService(properties, new JsonMapper()).search("Java 25 reference", 1);
    assertEquals("Java 25 reference", results.getFirst().title());
    assertEquals(1, first.get()); assertEquals(1, second.get());
  }
  @Test void queryExpansionsSharePhysicalRequestBudget() throws Exception {
    html("/empty", first, "unrelated result");
    properties.setProviders(List.of(provider("/empty"))); properties.setMaxSearchApiCalls(2);
    WebSearchSelection.search(new WebSearchService(properties, new JsonMapper()), new WebSearchQueryPlanner(), "rare evidence", 1, properties);
    assertEquals(2, first.get());
  }
  @Test void redirectsCannotBypassRequestQuota() {
    server.createContext("/loop", exchange -> {
      first.incrementAndGet(); exchange.getResponseHeaders().set("Location", "/loop");
      exchange.sendResponseHeaders(302, -1); exchange.close();
    });
    properties.setProviders(List.of(provider("/loop"))); properties.setMaxSearchApiCalls(2);
    var error = assertThrows(dev.mikoto2000.rei.http.HttpFetchException.class,
        () -> new WebSearchService(properties, new JsonMapper()).search("rare evidence", 1));
    assertEquals(dev.mikoto2000.rei.http.HttpFetchException.Code.REQUEST_BUDGET, error.code());
    assertEquals(2, first.get());
  }
}
