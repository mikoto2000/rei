package dev.mikoto2000.rei.chatgpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mikoto2000.rei.modeloptions.ChatPlanAccounts;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The shared account port reuses protected OAuth access and never changes account or model. */
class PlanAccountAccessTest {
  @TempDir Path root;
  private final Clock clock = Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC);

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
            "old-access",
            "old-refresh",
            Set.of("chatgpt.tokens.use.direct"),
            expired ? 0 : clock.instant().plusSeconds(3600).getEpochSecond());
    store()
        .locked(s -> {
          s.save("personal", tokens);
          return null;
        });
    return tokens;
  }

  private PlanOAuth oauth(PlanTransport transport) {
    return new PlanOAuth(store(), transport, (id, client, nonce) -> null, clock);
  }

  private ChatPlanAccounts accounts(PlanOAuth oauth, PlanTransport transport) {
    var client = new PlanClient(oauth, transport, clock);
    assertThat(client).isInstanceOf(ChatPlanAccounts.class);
    return (ChatPlanAccounts) (Object) client;
  }

  private String grant(String scope) {
    return "{\"token_type\":\"Bearer\",\"access_token\":\"new-access\","
        + "\"refresh_token\":\"new-refresh\",\"expires_in\":3600,\"scope\":\""
        + scope
        + "\"}";
  }

  @Test
  void localRegistrationLookupDoesNotRefreshAndSignedOutAccountsAreUnavailable() {
    var saved = register(true);
    var requests = new AtomicInteger();
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          requests.incrementAndGet();
          throw new AssertionError("Local registration observation dispatched HTTP");
        };
    var accounts = accounts(oauth(transport), transport);
    assertThat(accounts.registration("personal")).isEqualTo(saved.registrationId());
    assertThat(accounts.registration("missing")).isNull();
    store()
        .locked(s -> {
          s.save("personal", s.load("personal").withoutTokens());
          return null;
        });
    assertThat(accounts.registration("personal")).isNull();
    assertThat(requests).hasValue(0);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void staleRegistrationFailsBeforeRefreshOrModelDiscovery(boolean credential) {
    var saved = register(true);
    var requests = new AtomicInteger();
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          requests.incrementAndGet();
          throw new AssertionError("A stale registration reached HTTP");
        };
    var accounts = accounts(oauth(transport), transport);
    var failure =
        assertThrows(
            ChatGptException.class,
            () -> {
              if (credential)
                accounts.credential("personal", "old-" + saved.registrationId(), "allowed");
              else accounts.models("personal", "old-" + saved.registrationId());
            });
    assertEquals(ChatGptException.Code.AUTH_REQUIRED, failure.code());
    assertThat(requests).hasValue(0);
  }

  @Test
  void modelDiscoveryUsesTheExistingVisibleCatalogAndPreservesServerOrder() {
    var saved = register(false);
    var requests = new AtomicInteger();
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          requests.incrementAndGet();
          assertEquals("GET", method);
          assertEquals(PlanOAuth.RESOURCE + "/models", uri.toString());
          assertEquals("Bearer old-access", headers.get("Authorization"));
          return PlanOAuthTest.reply(
              200,
              "{\"models\":["
                  + "{\"slug\":\"z-visible\",\"display_name\":\"Z\",\"visibility\":\"list\"},"
                  + "{\"slug\":\"hidden\",\"display_name\":\"Hidden\",\"visibility\":\"hidden\"},"
                  + "{\"slug\":\"a-visible\",\"display_name\":\"A\",\"visibility\":\"list\"}]}");
        };
    var accounts = accounts(oauth(transport), transport);
    assertThat(accounts.models("personal", saved.registrationId()))
        .containsExactly("z-visible", "a-visible");
    assertThat(requests).hasValue(1);
  }

  @Test
  void lateCredentialRefreshRetainsRegistrationAndValidatesTheSelectedModel() {
    var saved = register(true);
    var refreshes = new AtomicInteger();
    var catalogs = new AtomicInteger();
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          if (uri.equals(PlanOAuth.TOKEN)) {
            assertEquals("POST", method);
            refreshes.incrementAndGet();
            return PlanOAuthTest.reply(200, grant("chatgpt.tokens.use.direct"));
          }
          assertEquals("GET", method);
          assertEquals(PlanOAuth.RESOURCE + "/models", uri.toString());
          assertEquals("Bearer new-access", headers.get("Authorization"));
          catalogs.incrementAndGet();
          return PlanOAuthTest.reply(200, PlanClientTest.MODELS);
        };
    var accounts = accounts(oauth(transport), transport);
    assertThat(accounts.credential("personal", saved.registrationId(), "allowed"))
        .isEqualTo("new-access");
    assertThat(accounts.registration("personal")).isEqualTo(saved.registrationId());
    assertThat(refreshes).hasValue(1);
    assertThat(catalogs).hasValue(1);
  }

  @Test
  void anUnavailableModelCannotReceiveACredentialOrTriggerInference() {
    var saved = register(false);
    var requests = new AtomicInteger();
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          requests.incrementAndGet();
          assertEquals("GET", method);
          assertEquals(PlanOAuth.RESOURCE + "/models", uri.toString());
          return PlanOAuthTest.reply(200, PlanClientTest.MODELS);
        };
    var accounts = accounts(oauth(transport), transport);
    var failure =
        assertThrows(
            ChatGptException.class,
            () -> accounts.credential("personal", saved.registrationId(), "unavailable-model"));
    assertEquals(ChatGptException.Code.MODEL_UNAVAILABLE, failure.code());
    assertThat(requests).hasValue(1);
  }

  @Test
  void refreshThatLosesPlanPermissionStopsBeforeReturningTheReplacementToken() {
    var saved = register(true);
    var requests = new AtomicInteger();
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          requests.incrementAndGet();
          assertEquals(PlanOAuth.TOKEN, uri);
          return PlanOAuthTest.reply(200, grant("openid profile"));
        };
    var oauth = oauth(transport);
    assertEquals(
        ChatGptException.Code.CONSENT_REQUIRED,
        assertThrows(ChatGptException.class, () -> oauth.access("personal")).code());
    var retained = store().locked(s -> s.load("personal"));
    assertThat(retained.clientId()).isEqualTo(saved.clientId());
    assertThat(retained.registrationId()).isEqualTo(saved.registrationId());
    assertThat(retained.accessToken()).isEqualTo("new-access");
    assertThat(retained.scopes()).doesNotContain("chatgpt.tokens.use.direct");
    assertThat(oauth.registration("personal")).isNull();
    assertThat(requests).hasValue(1);
  }
}
