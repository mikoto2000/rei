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

  private ChatPlanAccounts accounts() {
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
}
