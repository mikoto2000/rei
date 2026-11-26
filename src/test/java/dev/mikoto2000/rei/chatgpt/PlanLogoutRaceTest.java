package dev.mikoto2000.rei.chatgpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Logout intent fences new access and prevents a concurrent refresh from publishing credentials.
 */
class PlanLogoutRaceTest {
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
            "id",
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

  private static void awaitRelease(CountDownLatch release) {
    try {
      if (!release.await(15, TimeUnit.SECONDS))
        throw new AssertionError("Fixture was not released");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private PlanTransport.Response discovery() {
    return PlanOAuthTest.reply(200, "{\"revocation_endpoint\":\"https://auth.openai.com/revoke\"}");
  }

  @Test
  void logoutRejectsNewAccessWithoutWaitingForRemoteRevocation() throws Exception {
    var saved = register(false);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          if (method.equals("GET")) return discovery();
          entered.countDown();
          awaitRelease(release);
          return PlanOAuthTest.reply(200, "");
        };
    var oauth = new PlanOAuth(store(), transport, (id, client, nonce) -> null, Clock.systemUTC());
    var client = new PlanClient(oauth, transport, Clock.systemUTC());
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var logout = pool.submit(() -> oauth.logout("personal"));
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var lookup =
            pool.submit(() -> {
              assertEquals(
                  ChatGptException.Code.AUTH_REQUIRED,
                  assertThrows(
                          ChatGptException.class,
                          () -> oauth.access("personal", saved.registrationId()))
                      .code());
              assertEquals(
                  ChatGptException.Code.AUTH_REQUIRED,
                  assertThrows(
                          ChatGptException.class,
                          () -> client.begin("personal", saved.registrationId(), "model-a"))
                      .code());
              return oauth.registration("personal");
            });
        var observed = new AtomicReference<String>();
        assertThatCode(() -> observed.set(lookup.get(5, TimeUnit.SECONDS)))
            .doesNotThrowAnyException();
        assertThat(observed.get()).isNull();
      } finally {
        release.countDown();
        assertThat(logout.get(5, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  @Test
  void aRefreshAlreadyInFlightCannotPublishCredentialsAfterLogoutStarts() throws Exception {
    var saved = register(true);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          if (uri.equals(PlanOAuth.TOKEN)) {
            entered.countDown();
            awaitRelease(release);
            return PlanOAuthTest.reply(
                200,
                "{\"token_type\":\"Bearer\",\"access_token\":\"rotated-access\","
                    + "\"refresh_token\":\"rotated-refresh\",\"expires_in\":3600}");
          }
          if (method.equals("GET")) return discovery();
          assertThat(new String(body, java.nio.charset.StandardCharsets.UTF_8))
              .contains("token=rotated-refresh");
          return PlanOAuthTest.reply(200, "");
        };
    var oauth = new PlanOAuth(store(), transport, (id, client, nonce) -> null, Clock.systemUTC());
    var client = new PlanClient(oauth, transport, Clock.systemUTC());
    try (var pool = Executors.newVirtualThreadPerTaskExecutor();
        var request = client.begin("personal", saved.registrationId(), "model-a")) {
      request.invalidated().thenRun(request::close);
      var refreshing = pool.submit(() -> oauth.access("personal", saved.registrationId()));
      Future<Boolean> logout = null;
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        logout = pool.submit(() -> oauth.logout("personal"));
        assertThatCode(() -> request.invalidated().toCompletableFuture().get(5, TimeUnit.SECONDS))
            .doesNotThrowAnyException();
        release.countDown();
        var failure =
            assertThrows(ExecutionException.class, () -> refreshing.get(5, TimeUnit.SECONDS));
        assertThat(failure.getCause()).isInstanceOf(ChatGptException.class);
        assertEquals(
            ChatGptException.Code.AUTH_REQUIRED, ((ChatGptException) failure.getCause()).code());
      } finally {
        release.countDown();
        if (logout != null) assertThat(logout.get(5, TimeUnit.SECONDS)).isTrue();
      }
      var retained = store().locked(s -> s.load("personal"));
      assertThat(retained.clientId()).isEqualTo(saved.clientId());
      assertThat(retained.registrationId()).isEqualTo(saved.registrationId());
      assertThat(retained.accessToken()).isNull();
      assertThat(retained.refreshToken()).isNull();
    }
  }
}
