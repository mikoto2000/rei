package dev.mikoto2000.rei.httpapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.mikoto2000.rei.http.SafeHttpFetcher;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** REST read contracts against an explicitly allowed local fixture. */
class HttpApiReadTest {
  private HttpServer server;
  private String origin;
  private HttpApiService service;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    origin = "http://127.0.0.1:" + server.getAddress().getPort();
    service =
        new HttpApiService(
            new SafeHttpFetcher(),
            new HttpApiProperties(List.of(URI.create(origin)), Map.of(), List.of(), 1024, 2000));
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void respond(int status, String type, String body) {
    server.createContext(
        "/api",
        exchange -> {
          assertEquals("GET", exchange.getRequestMethod());
          assertEquals("q=a%20b%26c", exchange.getRequestURI().getRawQuery());
          exchange.getResponseHeaders().set("Content-Type", type);
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
          if (bytes.length > 0) exchange.getResponseBody().write(bytes);
          exchange.close();
        });
  }

  private HttpApiRequest request(String pointer, Integer max) {
    return new HttpApiRequest(
        origin + "/api",
        null,
        Map.of("q", "a b&c"),
        Map.of("Accept", "application/json"),
        pointer,
        max,
        null,
        null);
  }

  @Test
  void extractsEscapedPointerAndLimitsArray() {
    respond(200, "application/json", "{\"a/b\":{\"~list\":[1,2,3]}}");
    var result = service.read(request("/a~1b/~0list", 2));
    assertEquals(200, result.status());
    assertEquals("[1,2]", result.json().toString());
    assertTrue(result.extracted());
    assertTrue(result.truncated());
    assertNull(result.error());
  }

  @Test
  void retainsHttpErrorBodyAndStatus() {
    for (int status : new int[] {400, 401, 403, 404, 429, 500}) {
      respond(status, "application/json", "{\"error\":\"fixture\"}");
      var result = service.read(request(null, null));
      assertEquals(status, result.status());
      assertEquals("HTTP_ERROR", result.error());
      assertEquals("fixture", result.json().get("error").asText());
      server.removeContext("/api");
    }
  }

  @Test
  void distinguishesParseMissingPointerAndInvalidPointer() {
    respond(200, "application/json", "{\"items\":[]}");
    assertEquals("POINTER_NOT_FOUND", service.read(request("/absent", null)).error());
    assertEquals("INVALID_INPUT", service.read(request("/~2", null)).error());
    server.removeContext("/api");
    respond(200, "application/json", "{");
    assertEquals("INVALID_JSON", service.read(request("/items", null)).error());
  }

  @Test
  void handlesTextEmptyAndNoContent() {
    respond(200, "text/plain; charset=UTF-8", "日本語");
    assertEquals("日本語", service.read(request(null, null)).text());
    server.removeContext("/api");
    respond(204, "application/json", "");
    var result = service.read(request(null, null));
    assertEquals(204, result.status());
    assertNull(result.error());
    assertNull(result.json());
  }

  @Test
  void headDoesNotRejectRepresentationContentLength() {
    server.createContext(
        "/api",
        exchange -> {
          assertEquals("HEAD", exchange.getRequestMethod());
          exchange.getResponseHeaders().set("Content-Length", "9999999");
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    var req = new HttpApiRequest(origin + "/api", "HEAD", null, null, null, null, null, null);
    assertEquals(200, service.read(req).status());
    assertNull(service.read(req).error());
  }

  @Test
  void enforcesSizeBeforeParsingAndNeverReturnsPartialJson() {
    respond(200, "application/json", "[\"" + "x".repeat(2000) + "\"]");
    var result = service.read(request(null, null));
    assertEquals("WIRE_LIMIT", result.error());
    assertNull(result.json());
    assertNull(result.text());
  }

  @Test
  void rejectsUnapprovedLocalhostAndArbitraryHeaders() {
    var closed =
        new HttpApiService(
            new SafeHttpFetcher(), new HttpApiProperties(null, null, null, 1024, 2000));
    assertEquals("URL_NOT_ALLOWED", closed.read(request(null, null)).error());
    for (String header :
        List.of("Authorization", "Cookie", "Host", "X-Api-Key", "Connection", "Content-Length")) {
      assertEquals(
          "INVALID_INPUT",
          service
              .read(
                  new HttpApiRequest(
                      origin + "/api",
                      null,
                      null,
                      Map.of(header, "secret"),
                      null,
                      null,
                      null,
                      null))
              .error());
    }
    assertEquals(
        "INVALID_INPUT",
        service
            .read(new HttpApiRequest(origin + "/api", "POST", null, null, null, null, null, null))
            .error());
  }

  @Test
  void redirectsRevalidateOriginAndEachReadResource() {
    server.createContext(
        "/start",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "/api?q=a%20b%26c");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    respond(200, "application/json", "{\"count\":2}");
    var request = new HttpApiRequest(origin + "/start", null, null, null, "/count", 1, null, null);
    assertEquals("2", service.read(request).json().toString());
    var blocked =
        new HttpApiService(
            new SafeHttpFetcher(),
            new HttpApiProperties(
                List.of(URI.create(origin)),
                null,
                List.of(URI.create(origin + "/api")),
                1024,
                2000));
    assertEquals("URL_NOT_ALLOWED", blocked.read(request).error());
    server.removeContext("/start");
    server.createContext(
        "/start",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/private");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    assertEquals("URL_NOT_ALLOWED", service.read(request).error());
  }

  @Test
  void cancellationAndDeadlineAreDistinct() {
    var request = request(null, null);
    try (var scope =
        dev.mikoto2000.rei.http.FetchScope.enter(
            new dev.mikoto2000.rei.http.FetchOperation(
                () -> {
                  throw new java.util.concurrent.CancellationException();
                },
                Long.MAX_VALUE))) {
      var result = service.read(request);
      assertTrue(result.cancelled());
      assertFalse(result.timedOut());
    }
    try (var scope =
        dev.mikoto2000.rei.http.FetchScope.enter(
            new dev.mikoto2000.rei.http.FetchOperation(() -> {}, System.nanoTime() - 1))) {
      var result = service.read(request);
      assertEquals("TOTAL_TIMEOUT", result.error());
      assertTrue(result.timedOut());
      assertFalse(result.cancelled());
    }
  }
}
