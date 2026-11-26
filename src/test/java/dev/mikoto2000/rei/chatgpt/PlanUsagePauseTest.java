package dev.mikoto2000.rei.chatgpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mikoto2000.rei.modeloptions.ChatPlanAccounts;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** App usage pauses belong to one registration; neither a whole ChatGPT account nor a whole Run. */
class PlanUsagePauseTest {
  @TempDir Path root;
  private final AtomicInteger http = new AtomicInteger();

  private ProtectedCredentialStore store() {
    return new ProtectedCredentialStore(root, ProtectedCredentialStoreTest.TEST_PROTECTION);
  }

  private TokenSet register(String account) {
    var tokens = ProtectedCredentialStoreTest.tokens("access-secret", "refresh-secret");
    store()
        .locked(tx -> {
          tx.save(account, tokens);
          return null;
        });
    return tokens;
  }

  private PlanClient client() {
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          http.incrementAndGet();
          throw new AssertionError("Local usage state must not acquire credentials or call HTTP");
        };
    return new PlanClient(
        new PlanOAuth(store(), transport, (id, app, nonce) -> null, Clock.systemUTC()),
        transport,
        Clock.systemUTC());
  }

  private void pause(ChatPlanAccounts accounts, String account, String registration) {
    assertThatCode(() -> accounts.pauseRequests(account, registration)).doesNotThrowAnyException();
  }

  @Test
  void aConfirmedAppLimitStopsNewRequestsAcrossModelsWithoutInventingAPlanBalance() {
    var saved = register("personal");
    var accounts = client();
    pause(accounts, "personal", saved.registrationId());
    assertThat(accounts.requestsPaused("personal", saved.registrationId())).isTrue();
    for (var model : java.util.List.of("model-a", "model-b"))
      assertEquals(
          ChatGptException.Code.USAGE_LIMIT,
          assertThrows(
                  ChatGptException.class,
                  () -> accounts.begin("personal", saved.registrationId(), model))
              .code());
    assertThat(http).hasValue(0);
    assertThat(store().locked(tx -> tx.load("personal")).accessToken()).isEqualTo("access-secret");
  }

  @Test
  void otherAccountsAndRegistrationsAreNotPaused() {
    var personal = register("personal");
    var work = register("work");
    var accounts = client();
    pause(accounts, "personal", personal.registrationId());
    assertThat(accounts.requestsPaused("work", work.registrationId())).isFalse();
    try (var request = accounts.begin("work", work.registrationId(), "model")) {
      assertThat(request).isNotNull();
    }
    var replacement = register("personal");
    assertThat(accounts.requestsPaused("personal", replacement.registrationId())).isFalse();
    try (var request = accounts.begin("personal", replacement.registrationId(), "model")) {
      assertThat(request).isNotNull();
    }
    assertThat(http).hasValue(0);
  }

  @Test
  void explicitResumePermitsOneNewAttemptWithoutRefreshInferenceOrCredentialChanges() {
    var saved = register("personal");
    var accounts = client();
    pause(accounts, "personal", saved.registrationId());
    assertThatCode(() -> accounts.resumeRequests("personal", saved.registrationId()))
        .doesNotThrowAnyException();
    assertThat(accounts.requestsPaused("personal", saved.registrationId())).isFalse();
    try (var request = accounts.begin("personal", saved.registrationId(), "model")) {
      assertThat(request).isNotNull();
    }
    assertThat(http).hasValue(0);
    TokenSet current = store().locked(tx -> tx.load("personal"));
    assertThat(current).isEqualTo(saved);
  }

  @Test
  void staleRegistrationCannotPauseOrResumeAReplacement() {
    var old = register("personal");
    var accounts = client();
    var replacement = register("personal");
    assertEquals(
        ChatGptException.Code.AUTH_REQUIRED,
        assertThrows(
                ChatGptException.class,
                () -> accounts.pauseRequests("personal", old.registrationId()))
            .code());
    pause(accounts, "personal", replacement.registrationId());
    assertEquals(
        ChatGptException.Code.AUTH_REQUIRED,
        assertThrows(
                ChatGptException.class,
                () -> accounts.resumeRequests("personal", old.registrationId()))
            .code());
    assertThat(accounts.requestsPaused("personal", replacement.registrationId())).isTrue();
    assertThat(http).hasValue(0);
  }

  @Test
  void aUsagePauseDoesNotCancelAnAlreadyDispatchedRequestOrRetainItsSlot() {
    var saved = register("personal");
    var accounts = client();
    try (var active = accounts.begin("personal", saved.registrationId(), "model")) {
      pause(accounts, "personal", saved.registrationId());
      assertThat(active.invalidated().toCompletableFuture()).isNotDone();
    }
    accounts.resumeRequests("personal", saved.registrationId());
    try (var request = accounts.begin("personal", saved.registrationId(), "model")) {
      assertThat(request).isNotNull();
    }
    assertThat(http).hasValue(0);
  }
}
