package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlanClientTest {
  @TempDir Path root;

  PlanOAuth oauth(PlanTransport transport) {
    var store = new ProtectedCredentialStore(root, ProtectedCredentialStoreTest.TEST_PROTECTION);
    store.locked(
        s -> {
          s.save("default", ProtectedCredentialStoreTest.tokens("access-secret", "refresh-secret"));
          return null;
        });
    return new PlanOAuth(store, transport, (i, c, n) -> null, Clock.systemUTC());
  }

  static com.fasterxml.jackson.databind.JsonNode parse(byte[] bytes) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  static final String MODELS =
      "{\"models\":[{\"slug\":\"hidden\",\"display_name\":\"Hidden\",\"visibility\":\"hidden\"},{\"slug\":\"allowed\",\"display_name\":\"Allowed\",\"visibility\":\"list\"}]}";

  @Test
  void catalogPreservesVisibleModelsAndRefreshes() {
    var calls = new AtomicInteger();
    PlanTransport transport =
        (u, m, h, b, t, c) -> {
          calls.incrementAndGet();
          assertEquals("Bearer access-secret", h.get("Authorization"));
          return PlanOAuthTest.reply(200, MODELS);
        };
    var client = new PlanClient(oauth(transport), transport, Clock.systemUTC());
    assertEquals(
        List.of(new PlanClient.Model("allowed", "Allowed")), client.models("default", false));
    client.models("default", false);
    assertEquals(1, calls.get());
    client.models("default", true);
    assertEquals(2, calls.get());
  }

  @Test
  void unknownModelNeverSendsInference() {
    AtomicInteger calls = new AtomicInteger();
    PlanTransport transport =
        (u, m, h, b, t, c) -> {
          calls.incrementAndGet();
          return PlanOAuthTest.reply(200, MODELS);
        };
    var client = new PlanClient(oauth(transport), transport, Clock.systemUTC());
    assertEquals(
        ChatGptException.Code.MODEL_UNAVAILABLE,
        assertThrows(
                ChatGptException.class,
                () ->
                    client.infer(
                        "default",
                        "bad",
                        "instructions",
                        "task",
                        Duration.ofSeconds(1),
                        262144,
                        1048576,
                        () -> false))
            .code());
    assertEquals(1, calls.get());
  }

  @Test
  void responsesBodyContainsOnlyAllowedParameters() {
    PlanTransport transport =
        (u, m, h, b, t, c) -> {
          if (m.equals("GET")) return PlanOAuthTest.reply(200, MODELS);
          var node = parse(b);
          Set<String> fields = new HashSet<>();
          node.fieldNames().forEachRemaining(fields::add);
          assertEquals(Set.of("model", "instructions", "input", "store", "stream"), fields);
          assertFalse(node.get("store").asBoolean());
          assertTrue(node.get("stream").asBoolean());
          return new PlanTransport.Response(
              200,
              PlanSseAssemblerTest.stream(
                  PlanSseAssemblerTest.event("response.completed", PlanSseAssemblerTest.COMPLETED)),
              "req",
              "text/event-stream");
        };
    var client = new PlanClient(oauth(transport), transport, Clock.systemUTC());
    assertEquals(
        "hello",
        client
            .infer(
                "default",
                "allowed",
                "instructions",
                "task",
                Duration.ofSeconds(1),
                262144,
                1048576,
                () -> false)
            .text());
  }

  @Test
  void admissionErrorsAreSanitizedAndClassified() {
    for (int status : new int[] {401, 403, 429, 503}) {
      PlanTransport transport =
          (u, m, h, b, t, c) ->
              PlanOAuthTest.reply(status, "{\"detail\":\"Authorization: Bearer access-secret\"}");
      var client = new PlanClient(oauth(transport), transport, Clock.systemUTC());
      var error = assertThrows(ChatGptException.class, () -> client.models("default", true));
      assertFalse(error.toString().contains("secret"));
      assertNull(error.getCause());
      assertEquals(
          status == 401
              ? ChatGptException.Code.AUTH_INVALID
              : status == 403
                  ? ChatGptException.Code.PERMISSION_DENIED
                  : status == 429
                      ? ChatGptException.Code.USAGE_LIMIT
                      : ChatGptException.Code.ROUTE_UNAVAILABLE,
          error.code());
    }
  }

  @Test
  void emptyCatalogIsValidAndMalformedCatalogFails() {
    PlanTransport empty = (u, m, h, b, t, c) -> PlanOAuthTest.reply(200, "{\"models\":[]}");
    assertTrue(
        new PlanClient(oauth(empty), empty, Clock.systemUTC()).models("default", false).isEmpty());
    PlanTransport bad = (u, m, h, b, t, c) -> PlanOAuthTest.reply(200, "{\"data\":[]}");
    assertThrows(
        ChatGptException.class,
        () -> new PlanClient(oauth(bad), bad, Clock.systemUTC()).models("default", true));
  }

  @Test
  void sendLimitIsCheckedBeforeInference() {
    AtomicInteger calls = new AtomicInteger();
    PlanTransport transport =
        (u, m, h, b, t, c) -> {
          calls.incrementAndGet();
          return PlanOAuthTest.reply(200, MODELS);
        };
    var client = new PlanClient(oauth(transport), transport, Clock.systemUTC());
    assertEquals(
        ChatGptException.Code.SEND_LIMIT,
        assertThrows(
                ChatGptException.class,
                () ->
                    client.infer(
                        "default",
                        "allowed",
                        "instructions",
                        "task",
                        Duration.ofSeconds(1),
                        1,
                        1048576,
                        () -> false))
            .code());
    assertEquals(1, calls.get());
  }

  @Test
  void cancelledProbeDoesNotStart() {
    PlanTransport transport =
        (u, m, h, b, t, c) -> {
          throw new AssertionError();
        };
    var client = new PlanClient(oauth(transport), transport, Clock.systemUTC());
    assertEquals(
        ChatGptException.Code.CANCELLED,
        assertThrows(
                ChatGptException.class,
                () ->
                    client.infer(
                        "default",
                        "allowed",
                        "instructions",
                        "task",
                        Duration.ofSeconds(1),
                        262144,
                        1048576,
                        () -> true))
            .code());
  }

  @Test
  void oversizedHttpErrorsKeepStatusInsteadOfReceiveLimit() {
    for (int status : new int[] {401, 403, 429, 500, 503}) {
      PlanTransport transport =
          (u, m, h, b, t, c) ->
              PlanOAuthTest.reply(
                  status, "{\"detail\":\"access-secret" + "x".repeat(262144) + "\"}");
      var error =
          assertThrows(
              ChatGptException.class,
              () ->
                  new PlanClient(oauth(transport), transport, Clock.systemUTC())
                      .models("default", true));
      assertEquals(
          status == 500 ? ChatGptException.Code.TRANSPORT : PlanSseAssembler.classify("", status),
          error.code());
      assertFalse(error.toString().contains("access-secret"));
    }
  }

  @Test
  void incorrectContentTypeIsProtocolEvenForOversizedBody() {
    PlanTransport transport =
        (u, m, h, b, t, c) ->
            new PlanTransport.Response(
                200,
                new java.io.ByteArrayInputStream("x".repeat(262145).getBytes()),
                "secret-request",
                "text/html");
    assertEquals(
        ChatGptException.Code.PROTOCOL,
        assertThrows(
                ChatGptException.class,
                () ->
                    new PlanClient(oauth(transport), transport, Clock.systemUTC())
                        .models("default", true))
            .code());
  }

  @Test
  void catalogBoundariesCountUtf8BytesAndExposeOnlyObservedMetadata() {
    String base = MODELS.replace("Allowed", "日本語");
    int baseBytes = base.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    for (int size : new int[] {baseBytes, 262143, 262144, 262145}) {
      String body = base + " ".repeat(size - baseBytes);
      PlanTransport transport =
          (u, m, h, b, t, c) -> {
            assertEquals("https://api.openai.com/v1/models", u.toString());
            assertEquals("GET", m);
            assertEquals("application/json", h.get("Accept"));
            return PlanOAuthTest.reply(200, body);
          };
      var client = new PlanClient(oauth(transport), transport, Clock.systemUTC());
      if (size <= 262144) assertEquals("日本語", client.models("default", true).getFirst().name());
      else {
        var error = assertThrows(ChatGptException.class, () -> client.models("default", true));
        assertEquals(ChatGptException.Code.RECEIVE_LIMIT, error.code());
        assertTrue(error.getMessage().contains("operation=models, httpStatus=200"));
        assertTrue(error.getMessage().contains("receivedBytes=262145, receiveLimitBytes=262144"));
        assertTrue(error.getMessage().contains("bodyTruncated=true"));
        assertFalse(error.getMessage().contains("日本語"));
      }
    }
  }

  @Test
  void responseAdmissionHasSeparateBoundedErrorReadAndKeepsApiClassification() {
    for (String errorBody :
        new String[] {
          "{\"error\":{\"code\":\"subscription_sharing_usage_unavailable\",\"message\":\"access-secret"
              + " refresh-secret id-secret\"}}",
          "{\"detail\":\"" + "x".repeat(65537) + "\"}"
        }) {
      PlanTransport transport =
          (u, m, h, b, t, c) ->
              m.equals("GET")
                  ? PlanOAuthTest.reply(200, MODELS)
                  : PlanOAuthTest.reply(429, errorBody);
      var client = new PlanClient(oauth(transport), transport, Clock.systemUTC());
      var error =
          assertThrows(
              ChatGptException.class,
              () ->
                  client.infer(
                      "default",
                      "allowed",
                      "instructions",
                      "task",
                      Duration.ofSeconds(5),
                      262144,
                      1048576,
                      () -> false));
      assertEquals(
          errorBody.length() > 65536
              ? ChatGptException.Code.USAGE_LIMIT
              : ChatGptException.Code.USAGE_UNAVAILABLE,
          error.code());
      assertTrue(error.getMessage().contains("operation=responses, httpStatus=429"));
      assertTrue(error.getMessage().contains("receiveLimitBytes=65536"));
      assertFalse(error.toString().contains("secret"));
      assertNull(error.getCause());
      if (errorBody.length() <= 65536)
        assertTrue(
            error.getMessage().contains("apiErrorCode=subscription_sharing_usage_unavailable"));
    }
  }

  @Test
  void malformedJsonAndEncodedOrMissingMediaTypeFailSafely() {
    for (String body : new String[] {"{", MODELS + " {}"}) {
      PlanTransport transport = (u, m, h, b, t, c) -> PlanOAuthTest.reply(200, body);
      assertEquals(
          ChatGptException.Code.PROTOCOL,
          assertThrows(
                  ChatGptException.class,
                  () ->
                      new PlanClient(oauth(transport), transport, Clock.systemUTC())
                          .models("default", true))
              .code());
    }
    for (String type : new String[] {"", "application/json-access-secret", "text/event-stream"}) {
      PlanTransport transport =
          (u, m, h, b, t, c) ->
              new PlanTransport.Response(
                  200, new java.io.ByteArrayInputStream(MODELS.getBytes()), "request-secret", type);
      var error =
          assertThrows(
              ChatGptException.class,
              () ->
                  new PlanClient(oauth(transport), transport, Clock.systemUTC())
                      .models("default", true));
      assertEquals(ChatGptException.Code.PROTOCOL, error.code());
      assertFalse(error.toString().contains("secret"));
      assertFalse(error.toString().contains("receivedBytes="));
    }
    PlanTransport encoded =
        (u, m, h, b, t, c) ->
            new PlanTransport.Response(
                200,
                new java.io.ByteArrayInputStream(MODELS.getBytes()),
                null,
                "application/json",
                "gzip");
    assertEquals(
        ChatGptException.Code.PROTOCOL,
        assertThrows(
                ChatGptException.class,
                () ->
                    new PlanClient(oauth(encoded), encoded, Clock.systemUTC())
                        .models("default", true))
            .code());
  }

  @Test
  void statusMasksRegistrationAndSubjectWithoutNetwork() {
    PlanTransport transport =
        (u, m, h, b, t, c) -> {
          throw new AssertionError("No HTTP for status");
        };
    var oauth = oauth(transport);
    String status = oauth.status().getFirst();
    assertTrue(status.contains("ChatGPT plan authorized"));
    assertFalse(status.contains("oaiapp_test"));
    assertFalse(status.contains("access-secret"));
    assertFalse(status.contains("refresh-secret"));
    assertTrue(status.contains("[masked]"));
  }

  @Test
  void receiveLimitBeforeModelsIdentifiesOAuthRefreshWithoutClearingCredentials() {
    var store = new ProtectedCredentialStore(root, ProtectedCredentialStoreTest.TEST_PROTECTION);
    store.locked(
        s -> {
          s.save(
              "default",
              new TokenSet(
                  "oaiapp_test",
                  "subject",
                  null,
                  "id-secret",
                  "expired-secret",
                  "refresh-secret",
                  Set.of("chatgpt.tokens.use.direct"),
                  0));
          return null;
        });
    PlanTransport transport =
        (u, m, h, b, t, c) -> {
          assertTrue(u.getPath().endsWith("/oauth/token"));
          return PlanOAuthTest.reply(200, "x".repeat(65537));
        };
    var oauth = new PlanOAuth(store, transport, (i, c, n) -> null, Clock.systemUTC());
    var error =
        assertThrows(
            ChatGptException.class,
            () -> new PlanClient(oauth, transport, Clock.systemUTC()).models("default", true));
    assertEquals(ChatGptException.Code.RECEIVE_LIMIT, error.code());
    assertTrue(error.getMessage().contains("operation=oauth-token"));
    assertTrue(error.getMessage().contains("receivedBytes=65537, receiveLimitBytes=65536"));
    assertFalse(error.toString().contains("secret"));
    assertEquals("refresh-secret", store.locked(s -> s.load("default")).refreshToken());
  }
}
