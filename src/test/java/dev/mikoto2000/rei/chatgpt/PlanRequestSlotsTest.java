package dev.mikoto2000.rei.chatgpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mikoto2000.rei.modeloptions.ChatPlanAccounts;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Slots cover individual requests for one registration, independently of a whole Run. */
class PlanRequestSlotsTest {
  @TempDir Path root;
  private final AtomicInteger http = new AtomicInteger();

  private ProtectedCredentialStore store() {
    return new ProtectedCredentialStore(root, ProtectedCredentialStoreTest.TEST_PROTECTION);
  }

  private TokenSet register(String account, boolean expired) {
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
          s.save(account, tokens);
          return null;
        });
    return tokens;
  }

  private PlanClient accounts() {
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          http.incrementAndGet();
          throw new AssertionError("Request admission must not refresh or dispatch HTTP");
        };
    var oauth = new PlanOAuth(store(), transport, (id, client, nonce) -> null, Clock.systemUTC());
    return new PlanClient(oauth, transport, Clock.systemUTC());
  }

  private ChatPlanAccounts.Request begin(
      ChatPlanAccounts accounts, String account, String registration, String model) {
    var result = new AtomicReference<ChatPlanAccounts.Request>();
    assertThatCode(() -> result.set(accounts.begin(account, registration, model)))
        .doesNotThrowAnyException();
    return result.get();
  }

  @Test
  void completingTheParentInferenceReleasesItsSlotBeforeAChildStarts() {
    var saved = register("personal", true);
    var accounts = accounts();
    var parent = begin(accounts, "personal", saved.registrationId(), "model-a");
    try {
      assertEquals(
          ChatGptException.Code.BUSY,
          assertThrows(
                  ChatGptException.class,
                  () -> accounts.begin("personal", saved.registrationId(), "model-a"))
              .code());
      assertThat(parent.invalidated().toCompletableFuture()).isNotDone();
    } finally {
      parent.close();
    }
    assertThat(parent.invalidated().toCompletableFuture()).isNotDone();
    try (var child = begin(accounts, "personal", saved.registrationId(), "model-a")) {
      assertThat(child.invalidated().toCompletableFuture()).isNotDone();
    }
    assertThat(http).hasValue(0);
  }

  @Test
  void differentModelsShareTheSameRegistrationSlot() {
    var saved = register("personal", false);
    var accounts = accounts();
    try (var first = begin(accounts, "personal", saved.registrationId(), "model-a")) {
      assertThat(first).isNotNull();
      assertEquals(
          ChatGptException.Code.BUSY,
          assertThrows(
                  ChatGptException.class,
                  () -> accounts.begin("personal", saved.registrationId(), "model-b"))
              .code());
    }
    assertThat(http).hasValue(0);
  }

  @Test
  void differentAccountRegistrationsHaveIndependentCapacity() {
    var personal = register("personal", false);
    var work = register("work", false);
    var accounts = accounts();
    try (var first = begin(accounts, "personal", personal.registrationId(), "model-a");
        var second = begin(accounts, "work", work.registrationId(), "model-a")) {
      assertThat(first.invalidated().toCompletableFuture()).isNotDone();
      assertThat(second.invalidated().toCompletableFuture()).isNotDone();
    }
    assertThat(http).hasValue(0);
  }

  @Test
  void repeatedCloseDoesNotCreateExtraCapacity() {
    var saved = register("personal", false);
    var accounts = accounts();
    var first = begin(accounts, "personal", saved.registrationId(), "model-a");
    first.close();
    first.close();
    try (var second = begin(accounts, "personal", saved.registrationId(), "model-a")) {
      assertThat(second).isNotNull();
      assertEquals(
          ChatGptException.Code.BUSY,
          assertThrows(
                  ChatGptException.class,
                  () -> accounts.begin("personal", saved.registrationId(), "model-a"))
              .code());
    }
  }

  @Test
  void staleRegistrationDoesNotRefreshOrConsumeARequestSlot() {
    var saved = register("personal", true);
    var accounts = accounts();
    assertEquals(
        ChatGptException.Code.AUTH_REQUIRED,
        assertThrows(
                ChatGptException.class,
                () -> accounts.begin("personal", "old-" + saved.registrationId(), "model-a"))
            .code());
    try (var accepted = begin(accounts, "personal", saved.registrationId(), "model-a")) {
      assertThat(accepted).isNotNull();
    }
    assertThat(http).hasValue(0);
  }

  @Test
  void stoppingOneAccountInvalidatesItsRequestsAndAllowsEagerCleanupToReenterAdmission() {
    var personal = register("personal", false);
    var work = register("work", false);
    var client = accounts();
    try (var first = begin(client, "personal", personal.registrationId(), "model-a");
        var other = begin(client, "work", work.registrationId(), "model-a")) {
      first.invalidated().thenRun(first::close);
      client.stop("personal");
      assertThat(first.invalidated().toCompletableFuture()).isCompleted();
      assertThat(other.invalidated().toCompletableFuture()).isNotDone();
      try (var replacement = begin(client, "personal", personal.registrationId(), "model-a")) {
        assertThat(replacement.invalidated().toCompletableFuture()).isNotDone();
      }
    }
    assertThat(http).hasValue(0);
  }

  @Test
  void logoutInvalidatesRequestsBeforeWaitingForRemoteRevocation() throws Exception {
    var saved = register("personal", false);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          if (method.equals("GET"))
            return PlanOAuthTest.reply(
                200, "{\"revocation_endpoint\":\"https://auth.openai.com/revoke\"}");
          entered.countDown();
          try {
            if (!release.await(5, TimeUnit.SECONDS))
              throw new AssertionError("Revocation fixture was not released");
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
          }
          return PlanOAuthTest.reply(200, "");
        };
    var oauth = new PlanOAuth(store(), transport, (id, account, nonce) -> null, Clock.systemUTC());
    var client = new PlanClient(oauth, transport, Clock.systemUTC());
    try (var pool = Executors.newVirtualThreadPerTaskExecutor();
        var request = begin(client, "personal", saved.registrationId(), "model-a")) {
      request.invalidated().thenRun(request::close);
      var logout = pool.submit(() -> oauth.logout("personal"));
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(request.invalidated().toCompletableFuture()).isCompleted();
      } finally {
        release.countDown();
      }
      assertThat(logout.get(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}
