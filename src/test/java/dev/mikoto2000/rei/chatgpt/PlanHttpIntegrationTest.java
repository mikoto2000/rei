package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanHttpIntegrationTest {
  @TempDir Path root;

  @Test
  void oauthBearerCatalogAndFragmentedResponsesWorkTogether() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var requests = new AtomicInteger();
    server.createContext(
        "/v1/models",
        x -> {
          byte[] body = PlanClientTest.MODELS.getBytes(StandardCharsets.UTF_8);
          x.getResponseHeaders().set("Content-Type", "application/json");
          x.getResponseHeaders().set("Content-Type", "application/json");
          x.sendResponseHeaders(200, body.length);
          x.getResponseBody().write(body);
          x.close();
        });
    server.createContext(
        "/v1/responses",
        x -> {
          assertEquals("Bearer mock-access", x.getRequestHeaders().getFirst("Authorization"));
          requests.incrementAndGet();
          x.getRequestBody().readAllBytes();
          // Simulate the observed HTTP 200 response with no Content-Type.
          x.sendResponseHeaders(200, 0);
          for (byte b :
              (PlanSseAssemblerTest.delta("Hello, ")
                      + PlanSseAssemblerTest.delta("world!")
                      + PlanSseAssemblerTest.event(
                          "response.completed", PlanSseAssemblerTest.EMPTY_COMPLETED))
                  .getBytes(StandardCharsets.UTF_8)) {
            x.getResponseBody().write(b);
            x.getResponseBody().flush();
          }
          x.close();
        });
    server.start();
    try {
      var http = new PlanTransport.Http();
      PlanTransport local =
          (u, m, h, b, t, c) ->
              http.send(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort() + u.getPath()),
                  m,
                  h,
                  b,
                  t,
                  c);
      var store = new ProtectedCredentialStore(root, ProtectedCredentialStoreTest.TEST_PROTECTION);
      store.locked(
          s -> {
            s.save("default", ProtectedCredentialStoreTest.tokens("mock-access", "mock-refresh"));
            return null;
          });
      var oauth = new PlanOAuth(store, local, (i, c, n) -> null, Clock.systemUTC());
      var client = new PlanClient(oauth, local, Clock.systemUTC());
      var result =
          client.infer(
              "default",
              "allowed",
              "No tools",
              "Hello",
              Duration.ofSeconds(5),
              262144,
              1048576,
              () -> false);
      assertEquals(PlanSseAssembler.State.COMPLETED, result.state());
      assertEquals("Hello, world!", result.text());
      assertEquals(34, result.totalTokens());
      assertTrue(result.diagnostic().contains("textSource=stream"));
      assertEquals(1, requests.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void logoutGenerationStopsAnOpenStreamWithoutSuccess() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var opened = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    server.createContext(
        "/v1/models",
        x -> {
          var body = PlanClientTest.MODELS.getBytes(StandardCharsets.UTF_8);
          x.getResponseHeaders().set("Content-Type", "application/json");
          x.sendResponseHeaders(200, body.length);
          x.getResponseBody().write(body);
          x.close();
        });
    server.createContext(
        "/v1/responses",
        x -> {
          try {
            x.getRequestBody().readAllBytes();
            x.getResponseHeaders().set("Content-Type", "text/event-stream");
            x.sendResponseHeaders(200, 0);
            x.getResponseBody()
                .write(
                    "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"partial\"}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
            x.getResponseBody().flush();
            opened.countDown();
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            x.close();
          }
        });
    server.start();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var http = new PlanTransport.Http();
      PlanTransport local =
          (u, m, h, b, t, c) ->
              http.send(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort() + u.getPath()),
                  m,
                  h,
                  b,
                  t,
                  c);
      var store = new ProtectedCredentialStore(root, ProtectedCredentialStoreTest.TEST_PROTECTION);
      store.locked(
          s -> {
            s.save("default", ProtectedCredentialStoreTest.tokens("mock-access", "mock-refresh"));
            return null;
          });
      var client =
          new PlanClient(
              new PlanOAuth(store, local, (i, c, n) -> null, Clock.systemUTC()),
              local,
              Clock.systemUTC());
      var task =
          executor.submit(
              () ->
                  client.infer(
                      "default",
                      "allowed",
                      "No tools",
                      "Hello",
                      Duration.ofSeconds(5),
                      262144,
                      1048576,
                      () -> false));
      assertTrue(opened.await(5, TimeUnit.SECONDS));
      client.stop("default");
      var error = assertThrows(ExecutionException.class, () -> task.get(3, TimeUnit.SECONDS));
      assertEquals(ChatGptException.Code.CANCELLED, ((ChatGptException) error.getCause()).code());
      release.countDown();
    } finally {
      release.countDown();
      server.stop(0);
    }
  }

  @Test
  void catalogHttpStreamingHonorsTwoMiBBoundaryWithAndWithoutContentLength() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var size = new AtomicInteger(2097152);
    var chunked = new AtomicBoolean(true);
    server.createContext(
        "/v1/models",
        x -> {
          x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
          x.sendResponseHeaders(200, chunked.get() ? 0 : size.get());
          byte[] base = PlanClientTest.MODELS.getBytes(StandardCharsets.UTF_8);
          byte[] body = new byte[size.get()];
          java.util.Arrays.fill(body, (byte) ' ');
          System.arraycopy(base, 0, body, 0, base.length);
          try {
            for (int offset = 0; offset < body.length; offset += 997) {
              x.getResponseBody().write(body, offset, Math.min(997, body.length - offset));
              x.getResponseBody().flush();
            }
          } finally {
            x.close();
          }
        });
    server.start();
    try {
      var http = new PlanTransport.Http();
      PlanTransport local =
          (u, m, h, b, t, c) ->
              http.send(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort() + u.getPath()),
                  m,
                  h,
                  b,
                  t,
                  c);
      var store = new ProtectedCredentialStore(root, ProtectedCredentialStoreTest.TEST_PROTECTION);
      store.locked(
          s -> {
            s.save("default", ProtectedCredentialStoreTest.tokens("mock-access", "mock-refresh"));
            return null;
          });
      var client =
          new PlanClient(
              new PlanOAuth(store, local, (i, c, n) -> null, Clock.systemUTC()),
              local,
              Clock.systemUTC());
      for (boolean streaming : new boolean[] {true, false}) {
        chunked.set(streaming);
        size.set(2097152);
        assertEquals(1, client.models("default", true).size());
        size.set(2097153);
        var error = assertThrows(ChatGptException.class, () -> client.models("default", true));
        assertEquals(ChatGptException.Code.RECEIVE_LIMIT, error.code());
        assertTrue(error.getMessage().contains("receivedBytes=2097153, receiveLimitBytes=2097152"));
      }
    } finally {
      server.stop(0);
    }
  }
}
