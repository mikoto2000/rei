package dev.mikoto2000.rei.bluesky;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("integration")
class BlueskyRecordHttpTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String DID = "did:plc:example", KEY = "3jzfcijpj2z2a";
  private static final String CID = "bafyreidfayvfuwqa7qlnopdjiqrxzs6blmoeu4rujcjtnci5beludirz2a";
  private static final String RAW =
      "{\"$type\":\"app.bsky.feed.post\",\"text\":\"全文\\r\\n次の行\",\"createdAt\":\"2026-11-28T06:00:00Z\"}";
  private final AtomicInteger authCalls = new AtomicInteger(), createCalls = new AtomicInteger();
  private final List<Captured> captured = new CopyOnWriteArrayList<>();
  private final CountDownLatch bodyStarted = new CountDownLatch(1),
      releaseBody = new CountDownLatch(1);
  private ExecutorService workers;
  private HttpServer server;
  private HttpClient http;
  private DefaultBlueskyApiClient client;
  private volatile String authMode = "normal", writeMode = "normal";
  private volatile String authResponse =
      "{\"accessJwt\":\"fixture-access-jwt\",\"did\":\"" + DID + "\"}";
  private volatile String writeResponse =
      "{\"uri\":\"at://" + DID + "/app.bsky.feed.post/" + KEY + "\",\"cid\":\"" + CID + "\"}";
  private volatile int authStatus = 200, writeStatus = 200;

  @BeforeEach
  void prepare() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    workers = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(workers);
    server.createContext(
        "/xrpc/com.atproto.server.createSession", exchange -> respond(exchange, true));
    server.createContext(
        "/xrpc/com.atproto.repo.createRecord", exchange -> respond(exchange, false));
    server.start();
    var base = "http://127.0.0.1:" + server.getAddress().getPort();
    var properties = new BlueskyProperties();
    properties.setTimeoutSeconds(1);
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    client =
        new DefaultBlueskyApiClient(
            properties,
            http,
            URI.create(base + "/xrpc/com.atproto.server.createSession"),
            URI.create(base + "/xrpc/com.atproto.repo.createRecord"));
  }

  @AfterEach
  void close() {
    releaseBody.countDown();
    if (server != null) server.stop(0);
    if (workers != null) workers.shutdownNow();
    if (http != null) http.close();
  }

  @Test
  void authenticatesWithExistingValuesAndSendsTheExactKeyAndRecordWithoutFacets() throws Exception {
    var auth = client.authenticateForRecord("rei.example", "fixture-app-password");
    assertThat(auth.success()).isTrue();
    assertThat(auth.did()).isEqualTo(DID);
    var result = client.createRecord(auth.accessJwt(), DID, KEY, RAW);
    assertThat(result.uri()).isEqualTo("at://" + DID + "/app.bsky.feed.post/" + KEY);
    assertThat(result.cid()).isEqualTo(CID);
    assertThat(authCalls.get()).isEqualTo(1);
    assertThat(createCalls.get()).isEqualTo(1);
    var login = captured.get(0);
    var write = captured.get(1);
    assertThat(login.method()).isEqualTo("POST");
    assertThat(login.authorization()).isNull();
    assertThat(login.body().path("identifier").textValue()).isEqualTo("rei.example");
    assertThat(login.body().path("password").textValue()).isEqualTo("fixture-app-password");
    assertThat(write.method()).isEqualTo("POST");
    assertThat(write.authorization()).isEqualTo("Bearer fixture-access-jwt");
    assertThat(write.body().path("repo").textValue()).isEqualTo(DID);
    assertThat(write.body().path("collection").textValue()).isEqualTo("app.bsky.feed.post");
    assertThat(write.body().path("rkey").textValue()).isEqualTo(KEY);
    assertThat(write.body().path("validate").booleanValue()).isTrue();
    assertThat(write.body().path("record")).isEqualTo(JSON.readTree(RAW));
    assertThat(write.body().path("record").path("text").textValue()).isEqualTo("全文\r\n次の行");
    assertThat(write.body().path("record").size()).isEqualTo(3);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void responseDeadlineCoversAStartedAuthenticationOrRecordBody(boolean writing) {
    if (writing) writeMode = "drip";
    else authMode = "drip";
    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () -> {
          assertThatThrownBy(
                  () -> {
                    if (writing) client.createRecord("fixture-access-jwt", DID, KEY, RAW);
                    else client.authenticateForRecord("rei.example", "fixture-app-password");
                  })
              .isInstanceOf(IOException.class);
        });
    assertThat(bodyStarted.getCount()).isZero();
    assertThat(writing ? createCalls.get() : authCalls.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void responseBodiesAreBoundedBeforeDecoding(boolean writing) {
    if (writing) writeMode = "oversized";
    else authMode = "oversized";
    assertThatThrownBy(
            () -> {
              if (writing) client.createRecord("fixture-access-jwt", DID, KEY, RAW);
              else client.authenticateForRecord("rei.example", "fixture-app-password");
            })
        .isInstanceOf(IOException.class);
  }

  @Test
  void aLostCreationResponseDoesNotCauseAnImplicitSecondPost() {
    writeMode = "lost";
    assertThatThrownBy(() -> client.createRecord("fixture-access-jwt", DID, KEY, RAW))
        .isInstanceOf(IOException.class);
    assertThat(createCalls.get()).isEqualTo(1);
    assertThat(captured).hasSize(1);
    assertThat(captured.getFirst().body().path("record")).isEqualTo(parse(RAW));
  }

  @Test
  void invalidOrDuplicateResponseFieldsAreNotTreatedAsSuccess() {
    authResponse =
        "{\"accessJwt\":\"PRIVATE_FRAGMENT\",\"accessJwt\":\"other\",\"did\":\"" + DID + "\"}";
    assertThatThrownBy(() -> client.authenticateForRecord("rei.example", "fixture-app-password"))
        .isInstanceOf(IOException.class)
        .satisfies(error -> assertThat(error.getMessage()).doesNotContain("PRIVATE_FRAGMENT"));
    writeResponse = "{\"uri\":{\"unexpected\":true},\"cid\":\"PRIVATE_FRAGMENT\"}";
    assertThatThrownBy(() -> client.createRecord("fixture-access-jwt", DID, KEY, RAW))
        .isInstanceOf(IOException.class)
        .satisfies(error -> assertThat(error.getMessage()).doesNotContain("PRIVATE_FRAGMENT"));
    writeResponse = "{\"uri\":\"at://" + DID + "/app.bsky.feed.post/" + KEY + "\"}";
    assertThatThrownBy(() -> client.createRecord("fixture-access-jwt", DID, KEY, RAW))
        .isInstanceOf(IOException.class);
  }

  @Test
  void nonSuccessResponsesNeverExposeProviderBodies() {
    authStatus = 401;
    authResponse = "{\"error\":\"PRIVATE_AUTH_FRAGMENT\"}";
    assertThatThrownBy(() -> client.authenticateForRecord("rei.example", "fixture-app-password"))
        .isInstanceOf(IOException.class)
        .satisfies(error -> assertThat(error.getMessage()).doesNotContain("PRIVATE_AUTH_FRAGMENT"));
    writeStatus = 500;
    writeResponse = "{\"error\":\"PRIVATE_WRITE_FRAGMENT\"}";
    assertThatThrownBy(() -> client.createRecord("fixture-access-jwt", DID, KEY, RAW))
        .isInstanceOf(IOException.class)
        .satisfies(
            error -> assertThat(error.getMessage()).doesNotContain("PRIVATE_WRITE_FRAGMENT"));
    assertThat(createCalls.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void requiresJsonResponses(boolean writing) {
    if (writing) writeMode = "wrong_type";
    else authMode = "wrong_type";
    assertThatThrownBy(
            () -> {
              if (writing) client.createRecord("fixture-access-jwt", DID, KEY, RAW);
              else client.authenticateForRecord("rei.example", "fixture-app-password");
            })
        .isInstanceOf(IOException.class);
  }

  private void respond(HttpExchange exchange, boolean authentication) throws IOException {
    if (authentication) authCalls.incrementAndGet();
    else createCalls.incrementAndGet();
    var request = JSON.readTree(exchange.getRequestBody().readAllBytes());
    captured.add(
        new Captured(
            exchange.getRequestMethod(),
            exchange.getRequestHeaders().getFirst("Authorization"),
            request));
    String mode = authentication ? authMode : writeMode;
    if (mode.equals("lost")) {
      exchange.close();
      return;
    }
    String body = authentication ? authResponse : writeResponse;
    exchange
        .getResponseHeaders()
        .set(
            "Content-Type",
            mode.equals("wrong_type") ? "text/plain" : "application/json; charset=utf-8");
    if (mode.equals("drip")) {
      exchange.sendResponseHeaders(200, 0);
      try (var output = exchange.getResponseBody()) {
        output.write(body.substring(0, 1).getBytes(StandardCharsets.UTF_8));
        output.flush();
        bodyStarted.countDown();
        try {
          releaseBody.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException cancelled) {
          Thread.currentThread().interrupt();
        }
        output.write(body.substring(1).getBytes(StandardCharsets.UTF_8));
      } catch (IOException cancelled) {
        /* The client cancelled its bounded response. */
      }
    } else {
      if (mode.equals("oversized")) body += " ".repeat(65536);
      var bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(authentication ? authStatus : writeStatus, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      } catch (IOException cancelled) {
        /* The client may reject an oversized response early. */
      }
    }
    exchange.close();
  }

  private static JsonNode parse(String raw) {
    try {
      return JSON.readTree(raw);
    } catch (IOException invalid) {
      throw new AssertionError(invalid);
    }
  }

  private record Captured(String method, String authorization, JsonNode body) {}
}
