package dev.mikoto2000.rei.chatgpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Narrowing old registration notifications must retain direct transport cancellation. */
class PlanRegistrationDirectCancellationTest {
  @TempDir Path root;

  @Test
  void registrationChangeReachesTheDirectTransportCancellationSignal() {
    var oauth = new AtomicReference<PlanOAuth>();
    var store = new ProtectedCredentialStore(root, ProtectedCredentialStoreTest.TEST_PROTECTION);
    var saved = ProtectedCredentialStoreTest.tokens("old-access", "old-refresh");
    store.locked(tx -> {
      tx.save("personal", saved);
      return null;
    });
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          if (method.equals("GET")) return PlanOAuthTest.reply(200, PlanClientTest.MODELS);
          if (!uri.getPath().endsWith("/responses"))
            return PlanOAuthTest.reply(
                200,
                "{\"token_type\":\"Bearer\",\"access_token\":\"new-access\",\"id_token\":\"id-new\","
                    + "\"refresh_token\":\"new-refresh\",\"expires_in\":3600,\"scope\":\"chatgpt.tokens.use.direct\"}");
          var attempt =
              oauth.get().begin("personal", URI.create("http://127.0.0.1:54321/auth/callback"));
          oauth.get().complete(attempt, Map.of("state", attempt.state(), "code", "fixture-code"));
          assertTrue(
              cancelled.getAsBoolean(),
              "The old inference must still observe registration invalidation");
          return new PlanTransport.Response(
              200,
              PlanSseAssemblerTest.stream(
                  PlanSseAssemblerTest.event("response.completed", PlanSseAssemblerTest.COMPLETED)),
              "fixture",
              "text/event-stream");
        };
    oauth.set(
        new PlanOAuth(
            store,
            transport,
            (id, client, nonce) -> new PlanOAuth.Identity(saved.subject(), saved.email()),
            Clock.systemUTC()));
    var accounts = new PlanClient(oauth.get(), transport, Clock.systemUTC());
    assertEquals(
        ChatGptException.Code.CANCELLED,
        assertThrows(
                ChatGptException.class,
                () ->
                    accounts.inferHistory(
                        "personal",
                        "allowed",
                        "fixture",
                        java.util.List.of(new PlanClient.Input("user", "fixture")),
                        Duration.ofSeconds(2),
                        262144,
                        1048576,
                        () -> false,
                        () -> {}))
            .code());
    var registration = accounts.registration("personal");
    assertThat(registration).isNotEqualTo(saved.registrationId());
    try (var replacement = accounts.begin("personal", registration, "allowed")) {
      assertThat(replacement.invalidated().toCompletableFuture()).isNotDone();
    }
  }
}
