package dev.mikoto2000.rei.chatgpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.modeloptions.ChatPlanAccounts.Invalidation;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A notification delayed after store unlock belongs to the old registration, not its replacement.
 */
class PlanInvalidationRegistrationRaceTest {
  @TempDir Path root;
  private final Clock clock = Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC);

  private String grant(boolean direct) {
    return "{\"token_type\":\"Bearer\",\"access_token\":\"new-access\",\"id_token\":\"id-new\","
        + "\"refresh_token\":\"new-refresh\",\"expires_in\":3600,\"scope\":\""
        + (direct ? "chatgpt.tokens.use.direct" : "openid profile")
        + "\"}";
  }

  private void signIn(PlanOAuth oauth) {
    var attempt = oauth.begin("personal", URI.create("http://127.0.0.1:54321/auth/callback"));
    oauth.complete(attempt, Map.of("state", attempt.state(), "code", "fixture-code"));
  }

  @ParameterizedTest
  @EnumSource(
      value = Invalidation.class,
      names = {"REGISTRATION_CHANGED", "AUTHENTICATION_INVALID", "CONSENT_REQUIRED"})
  void delayedOldRegistrationNotificationCannotStopNewlyAdmittedRequests(Invalidation reason)
      throws Exception {
    var store = new ProtectedCredentialStore(root, ProtectedCredentialStoreTest.TEST_PROTECTION);
    var old =
        new TokenSet(
            "oaiapp_test",
            "subject",
            null,
            "id-old",
            "old-access",
            "old-refresh",
            Set.of("chatgpt.tokens.use.direct"),
            reason == Invalidation.REGISTRATION_CHANGED
                ? clock.instant().plusSeconds(3600).getEpochSecond()
                : 0);
    store.locked(tx -> {
      tx.save("personal", old);
      return null;
    });
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          boolean refresh =
              new String(body, StandardCharsets.UTF_8).contains("grant_type=refresh_token");
          if (refresh && reason == Invalidation.AUTHENTICATION_INVALID)
            return PlanOAuthTest.reply(400, "{\"error\":\"invalid_grant\"}");
          return PlanOAuthTest.reply(
              200, grant(!refresh || reason != Invalidation.CONSENT_REQUIRED));
        };
    var oauth =
        new PlanOAuth(
            store,
            transport,
            (id, client, nonce) -> new PlanOAuth.Identity("subject", null),
            clock);
    var delayed = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    // Register before PlanClient so the notification is held after the store has been unlocked.
    oauth.onInvalidated((account, actual) -> {
      if (actual != reason) return;
      delayed.countDown();
      try {
        assertTrue(release.await(5, TimeUnit.SECONDS));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    });
    var client = new PlanClient(oauth, transport, clock);
    try (var pool = Executors.newSingleThreadExecutor();
        var previous = client.begin("personal", old.registrationId(), "model")) {
      var notification =
          pool.submit(() -> {
            if (reason == Invalidation.REGISTRATION_CHANGED) signIn(oauth);
            else
              assertEquals(
                  reason == Invalidation.AUTHENTICATION_INVALID
                      ? ChatGptException.Code.AUTH_INVALID
                      : ChatGptException.Code.CONSENT_REQUIRED,
                  assertThrows(ChatGptException.class, () -> oauth.access("personal")).code());
          });
      try {
        assertTrue(delayed.await(5, TimeUnit.SECONDS));
        if (reason != Invalidation.REGISTRATION_CHANGED) signIn(oauth);
        var current = oauth.registration("personal");
        assertThat(current).isNotNull().isNotEqualTo(old.registrationId());
        try (var replacement = client.begin("personal", current, "model")) {
          release.countDown();
          notification.get(5, TimeUnit.SECONDS);
          assertThat(previous.invalidated().toCompletableFuture()).isCompleted();
          assertThat(replacement.invalidated().toCompletableFuture()).isNotDone();
        }
      } finally {
        release.countDown();
        notification.cancel(true);
      }
    }
  }
}
