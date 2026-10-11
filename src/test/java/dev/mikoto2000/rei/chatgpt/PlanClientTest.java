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
}
