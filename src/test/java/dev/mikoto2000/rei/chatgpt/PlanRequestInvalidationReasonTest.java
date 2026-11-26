package dev.mikoto2000.rei.chatgpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mikoto2000.rei.modeloptions.ChatPlanAccounts.Invalidation;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Cancellation signals retain the account reason when they race the originating model failure. */
class PlanRequestInvalidationReasonTest {
  @TempDir Path root;

  private ProtectedCredentialStore store() {
    return new ProtectedCredentialStore(root, ProtectedCredentialStoreTest.TEST_PROTECTION);
  }

  private TokenSet register(boolean expired) {
    var tokens =
        new TokenSet(
            "oaiapp_test",
            "subject",
            null,
            "id-old",
            "access",
            "refresh",
            Set.of("chatgpt.tokens.use.direct"),
            expired ? 0 : Instant.now().plusSeconds(3600).getEpochSecond());
    store()
        .locked(s -> {
          s.save("personal", tokens);
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

  @ParameterizedTest
  @EnumSource(Invalidation.class)
  void aRequestSignalPreservesItsFirstLocalInvalidationReason(Invalidation reason) {
    var saved =
        register(
            reason == Invalidation.AUTHENTICATION_INVALID
                || reason == Invalidation.CONSENT_REQUIRED);
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          if (method.equals("GET"))
            return PlanOAuthTest.reply(
                200, "{\"revocation_endpoint\":\"https://auth.openai.com/revoke\"}");
          if (uri.equals(PlanOAuth.TOKEN)) {
            if (reason == Invalidation.AUTHENTICATION_INVALID)
              return PlanOAuthTest.reply(400, "{\"error\":\"invalid_grant\"}");
            return PlanOAuthTest.reply(
                200,
                grant(
                    reason == Invalidation.CONSENT_REQUIRED
                        ? "openid profile"
                        : "chatgpt.tokens.use.direct"));
          }
          return PlanOAuthTest.reply(200, "");
        };
    var oauth =
        new PlanOAuth(
            store(),
            transport,
            (id, client, nonce) -> new PlanOAuth.Identity("subject", null),
            Clock.systemUTC());
    var client = new PlanClient(oauth, transport, Clock.systemUTC());
    try (var request = client.begin("personal", saved.registrationId(), "model")) {
      var signal = request.invalidated().toCompletableFuture();
      switch (reason) {
        case STOPPED -> client.stop("personal");
        case SIGNED_OUT -> assertThat(oauth.logout("personal")).isTrue();
        case REGISTRATION_CHANGED -> {
          var attempt = oauth.begin("personal", URI.create("http://127.0.0.1:54321/auth/callback"));
          oauth.complete(attempt, Map.of("state", attempt.state(), "code", "fixture-code"));
        }
        case AUTHENTICATION_INVALID ->
            assertEquals(
                ChatGptException.Code.AUTH_INVALID,
                assertThrows(ChatGptException.class, () -> oauth.access("personal")).code());
        case CONSENT_REQUIRED ->
            assertEquals(
                ChatGptException.Code.CONSENT_REQUIRED,
                assertThrows(ChatGptException.class, () -> oauth.access("personal")).code());
      }
      assertThat(signal).isCompleted();
      assertThat((Object) signal.join()).isEqualTo(reason);
      client.stop("personal");
      assertThat((Object) signal.join()).isEqualTo(reason);
    }
  }

  @Test
  void normalReleaseDoesNotManufactureAnInvalidationReason() {
    var saved = register(false);
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          throw new AssertionError("Admission dispatched HTTP");
        };
    var oauth = new PlanOAuth(store(), transport, (id, client, nonce) -> null, Clock.systemUTC());
    var client = new PlanClient(oauth, transport, Clock.systemUTC());
    var request = client.begin("personal", saved.registrationId(), "model");
    request.close();
    assertThat(request.invalidated().toCompletableFuture()).isNotDone();
  }
}
