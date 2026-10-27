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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Writes are single attempts and credentials never escape their configured origin. */
class HttpApiWriteTest {
  private HttpServer server;
  private String origin;
  private HttpApiService service;
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    origin = "http://127.0.0.1:" + server.getAddress().getPort();
    var profiles =
        Map.of(
            "fixture",
            new HttpApiProperties.AuthProfile(URI.create(origin), "bearer", null, "FIXTURE_SECRET"),
            "key",
            new HttpApiProperties.AuthProfile(
                URI.create(origin), "api-key", "X-Api-Key", "FIXTURE_SECRET"));
    service =
        new HttpApiService(
            new SafeHttpFetcher(),
            new HttpApiProperties(List.of(URI.create(origin)), profiles, null, 4096, 1000),
            name -> "test-secret");
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void sendsStructuredBodiesForAllWriteMethods() throws Exception {
    for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
      for (String json : List.of("{\"title\":\"日本語\"}", "[1,2]", "null", "")) {
        server.createContext(
            "/api",
            exchange -> {
              assertEquals(method, exchange.getRequestMethod());
              assertEquals(
                  json,
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
              if (!json.isEmpty())
                assertEquals(
                    "application/json", exchange.getRequestHeaders().getFirst("Content-Type"));
              assertEquals("same-key", exchange.getRequestHeaders().getFirst("Idempotency-Key"));
              exchange.sendResponseHeaders(204, -1);
              exchange.close();
            });
        var result =
            service.write(
                new HttpApiRequest(
                    origin + "/api",
                    method,
                    null,
                    Map.of("Idempotency-Key", "same-key"),
                    null,
                    null,
                    null,
                    json.isEmpty() ? null : mapper.readTree(json)));
        assertEquals(204, result.status());
        assertNull(result.error());
        server.removeContext("/api");
      }
    }
  }

  @Test
  void appliesBothAuthTypesAndRedactsEchoedSecret() {
    server.createContext(
        "/api",
        exchange -> {
          var bearer = exchange.getRequestHeaders().getFirst("Authorization");
          var key = exchange.getRequestHeaders().getFirst("X-Api-Key");
          assertTrue("Bearer test-secret".equals(bearer) || "test-secret".equals(key));
          byte[] body = "{\"echo\":\"test-secret\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    for (String profile : List.of("fixture", "key")) {
      var result =
          service.read(
              new HttpApiRequest(origin + "/api", "GET", null, null, null, null, profile, null));
      assertEquals(200, result.status());
      assertFalse(result.toString().contains("test-secret"));
      assertEquals("[REDACTED]", result.json().get("echo").asText());
    }
  }

  @Test
  void refusesUnknownProfileOrDifferentOriginBeforeSending() {
    assertEquals(
        "AUTH_PROFILE_NOT_FOUND",
        service
            .read(
                new HttpApiRequest(origin + "/api", null, null, null, null, null, "unknown", null))
            .error());
    assertEquals(
        "AUTH_ORIGIN_MISMATCH",
        service
            .read(
                new HttpApiRequest(
                    "http://127.0.0.1:1/api", null, null, null, null, null, "fixture", null))
            .error());
  }

  @Test
  void neverRetriesWriteErrorsOrFollowsWriteRedirects() {
    var requests = new AtomicInteger();
    server.createContext(
        "/api",
        exchange -> {
          requests.incrementAndGet();
          exchange.getResponseHeaders().set("Location", "/other");
          exchange.sendResponseHeaders(307, -1);
          exchange.close();
        });
    var result =
        service.write(
            new HttpApiRequest(origin + "/api", "POST", null, null, null, null, "fixture", null));
    assertEquals(307, result.status());
    assertEquals("WRITE_REDIRECT_REFUSED", result.error());
    assertEquals(1, requests.get());
    server.removeContext("/api");
    server.createContext(
        "/api",
        exchange -> {
          requests.incrementAndGet();
          exchange.sendResponseHeaders(503, -1);
          exchange.close();
        });
    assertEquals(
        503,
        service
            .write(new HttpApiRequest(origin + "/api", "POST", null, null, null, null, null, null))
            .status());
    assertEquals(2, requests.get());
  }

  @Test
  void authenticatedReadCannotRedirectToDifferentOrigin() {
    server.createContext(
        "/api",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/steal");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    var result =
        service.read(
            new HttpApiRequest(origin + "/api", null, null, null, null, null, "fixture", null));
    assertEquals("URL_NOT_ALLOWED", result.error());
  }

  @Test
  void unicodeEscapingCannotExposeReflectedCredentials() {
    server.createContext(
        "/api",
        exchange -> {
          byte[] body =
              ("{\"echo\":\"" + "\\u0074est-secret" + "\"}").getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    var result =
        service.read(
            new HttpApiRequest(origin + "/api", null, null, null, null, null, "fixture", null));
    assertFalse(result.toString().contains("test-secret"));
  }
}
