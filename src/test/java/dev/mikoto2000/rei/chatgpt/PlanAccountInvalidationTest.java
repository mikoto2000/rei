package dev.mikoto2000.rei.chatgpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Confirmed account changes invalidate requests; transient and stale callers cannot stop others.
 */
class PlanAccountInvalidationTest {
  @TempDir Path root;
  private final Clock clock = Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC);

  private ProtectedCredentialStore store() {
    return new ProtectedCredentialStore(root, ProtectedCredentialStoreTest.TEST_PROTECTION);
  }

  private TokenSet register(String account, boolean expired) {
    var tokens =
        new TokenSet(
            "oaiapp_test",
            "subject",
            null,
            "id-old",
            "access",
            "refresh-secret",
            Set.of("chatgpt.tokens.use.direct"),
            expired ? 0 : clock.instant().plusSeconds(3600).getEpochSecond());
    store()
        .locked(s -> {
          s.save(account, tokens);
          return null;
        });
    return tokens;
  }

  private String grant(String scope) {
    return "{\"token_type\":\"Bearer\",\"access_token\":\"new-access\",\"id_token\":\"id-new\","
        + "\"refresh_token\":\"new-refresh\",\"expires_in\":3600,\"scope\":\""
        + scope
        + "\"}";
  }

  private PlanOAuth oauth(PlanTransport transport) {
    return new PlanOAuth(
        store(), transport, (id, client, nonce) -> new PlanOAuth.Identity("subject", null), clock);
  }

  @Test
  void successfulReauthorizationInvalidatesRequestsFromThePreviousRegistration() {
    var saved = register("personal", false);
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) ->
            PlanOAuthTest.reply(200, grant("chatgpt.tokens.use.direct"));
    var oauth = oauth(transport);
    var client = new PlanClient(oauth, transport, clock);
    try (var request = client.begin("personal", saved.registrationId(), "model")) {
      var attempt = oauth.begin("personal", URI.create("http://127.0.0.1:54321/auth/callback"));
      oauth.complete(attempt, Map.of("state", attempt.state(), "code", "fixture-code"));
      assertThat(oauth.registration("personal")).isNotEqualTo(saved.registrationId());
      assertThat(request.invalidated().toCompletableFuture()).isCompleted();
    }
  }

  @Test
  void terminalRefreshFailureInvalidatesOnlyTheAffectedAccount() {
    var personal = register("personal", true);
    var work = register("work", false);
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) ->
            PlanOAuthTest.reply(400, "{\"error\":\"invalid_grant\"}");
    var oauth = oauth(transport);
    var client = new PlanClient(oauth, transport, clock);
    try (var first = client.begin("personal", personal.registrationId(), "model");
        var other = client.begin("work", work.registrationId(), "model")) {
      assertEquals(
          ChatGptException.Code.AUTH_INVALID,
          assertThrows(ChatGptException.class, () -> oauth.access("personal")).code());
      assertThat(first.invalidated().toCompletableFuture()).isCompleted();
      assertThat(other.invalidated().toCompletableFuture()).isNotDone();
      var retained = store().locked(s -> s.load("personal"));
      assertThat(retained.clientId()).isEqualTo(personal.clientId());
      assertThat(retained.accessToken()).isNull();
    }
  }

  @Test
  void losingPlanPermissionInvalidatesRequestsButRetainsTheSignedInIdentity() {
    var saved = register("personal", true);
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) ->
            PlanOAuthTest.reply(200, grant("openid profile"));
    var oauth = oauth(transport);
    var client = new PlanClient(oauth, transport, clock);
    try (var request = client.begin("personal", saved.registrationId(), "model")) {
      assertEquals(
          ChatGptException.Code.CONSENT_REQUIRED,
          assertThrows(ChatGptException.class, () -> oauth.access("personal")).code());
      assertThat(request.invalidated().toCompletableFuture()).isCompleted();
      var retained = store().locked(s -> s.load("personal"));
      assertThat(retained.clientId()).isEqualTo(saved.clientId());
      assertThat(retained.accessToken()).isEqualTo("new-access");
      assertThat(oauth.registration("personal")).isNull();
    }
  }

  @Test
  void temporaryRefreshFailurePreservesCredentialsAndOtherInFlightRequests() {
    var saved = register("personal", true);
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) ->
            PlanOAuthTest.reply(503, "{\"error\":\"temporarily_unavailable\"}");
    var oauth = oauth(transport);
    var client = new PlanClient(oauth, transport, clock);
    try (var request = client.begin("personal", saved.registrationId(), "model")) {
      assertEquals(
          ChatGptException.Code.TRANSPORT,
          assertThrows(ChatGptException.class, () -> oauth.access("personal")).code());
      assertThat(request.invalidated().toCompletableFuture()).isNotDone();
      var retained = store().locked(s -> s.load("personal"));
      assertThat(retained.accessToken()).isEqualTo("access");
      assertThat(retained.refreshToken()).isEqualTo("refresh-secret");
    }
  }

  @Test
  void aStaleRunCannotInvalidateTheCurrentRegistration() {
    var saved = register("personal", false);
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          throw new AssertionError("A stale account reached HTTP");
        };
    var oauth = oauth(transport);
    var client = new PlanClient(oauth, transport, clock);
    try (var request = client.begin("personal", saved.registrationId(), "model")) {
      assertEquals(
          ChatGptException.Code.AUTH_REQUIRED,
          assertThrows(
                  ChatGptException.class,
                  () -> client.credential("personal", "old-" + saved.registrationId(), "model"))
              .code());
      assertThat(request.invalidated().toCompletableFuture()).isNotDone();
    }
  }

  @Test
  void aRejectedLoginCannotInvalidateTheExistingRegistration() {
    var saved = register("personal", false);
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) ->
            PlanOAuthTest.reply(200, grant("chatgpt.tokens.use.direct"));
    var oauth =
        new PlanOAuth(
            store(),
            transport,
            (id, client, nonce) -> {
              throw ChatGptException.of(ChatGptException.Code.AUTH_INVALID);
            },
            clock);
    var client = new PlanClient(oauth, transport, clock);
    try (var request = client.begin("personal", saved.registrationId(), "model")) {
      var attempt = oauth.begin("personal", URI.create("http://127.0.0.1:54321/auth/callback"));
      assertEquals(
          ChatGptException.Code.AUTH_INVALID,
          assertThrows(
                  ChatGptException.class,
                  () ->
                      oauth.complete(
                          attempt, Map.of("state", attempt.state(), "code", "fixture-code")))
              .code());
      assertThat(oauth.registration("personal")).isEqualTo(saved.registrationId());
      assertThat(request.invalidated().toCompletableFuture()).isNotDone();
    }
  }
}
