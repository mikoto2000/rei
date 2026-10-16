package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanOAuthTest {
  @TempDir Path root;
  final Clock clock = Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC);

  ProtectedCredentialStore store() {
    return new ProtectedCredentialStore(root, ProtectedCredentialStoreTest.TEST_PROTECTION);
  }

  static PlanTransport.Response reply(int status, String body) {
    return new PlanTransport.Response(
        status,
        new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        "req",
        "application/json");
  }

  String grant(String access, String refresh) {
    return "{\"access_token\":\""
        + access
        + "\",\"refresh_token\":\""
        + refresh
        + "\",\"id_token\":\"id\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"chatgpt.tokens.use.direct"
        + " offline_access\"}";
  }

  @Test
  void newRegistrationExchangesIssuedClientAndValidatesNonce() {
    AtomicReference<String> form = new AtomicReference<>();
    AtomicReference<String> nonce = new AtomicReference<>();
    var oauth =
        new PlanOAuth(
            store(),
            (u, m, h, b, t, c) -> {
              form.set(new String(b));
              return reply(200, grant("access", "refresh"));
            },
            (id, client, n) -> {
              nonce.set(n);
              assertEquals("oaiapp_issued", client);
              return new PlanOAuth.Identity("sub", null);
            },
            clock);
    var attempt = oauth.begin("default", URI.create("http://127.0.0.1:54321/auth/callback"));
    assertTrue(attempt.authorization().toString().contains("client_id=dynamic_agent_client"));
    assertTrue(
        attempt
            .authorization()
            .toString()
            .contains("code_challenge=" + PlanOAuth.challenge(attempt.verifier())));
    oauth.complete(
        attempt, Map.of("code", "code", "state", attempt.state(), "client_id", "oaiapp_issued"));
    assertTrue(form.get().contains("client_id=oaiapp_issued"));
    assertTrue(form.get().contains("code_verifier=" + attempt.verifier()));
    assertEquals(attempt.nonce(), nonce.get());
    assertEquals("access", oauth.access("default").accessToken());
    assertThrows(
        ChatGptException.class,
        () ->
            oauth.complete(
                attempt,
                Map.of("code", "code", "state", attempt.state(), "client_id", "oaiapp_issued")));
  }

  @Test
  void stateAndMissingIssuedClientFailBeforeExchange() {
    AtomicInteger requests = new AtomicInteger();
    var oauth =
        new PlanOAuth(
            store(),
            (u, m, h, b, t, c) -> {
              requests.incrementAndGet();
              throw new AssertionError();
            },
            (i, c, n) -> {
              throw new AssertionError();
            },
            clock);
    var attempt = oauth.begin("default", URI.create("http://127.0.0.1:1455/auth/callback"));
    assertThrows(
        ChatGptException.class,
        () ->
            oauth.complete(
                attempt, Map.of("state", "bad", "code", "code", "client_id", "oaiapp_new")));
    assertThrows(
        ChatGptException.class,
        () -> oauth.complete(attempt, Map.of("state", attempt.state(), "code", "code")));
    assertEquals(0, requests.get());
  }

  @Test
  void serverRejectedPkceGrantCannotCreateCredentials() {
    var oauth =
        new PlanOAuth(
            store(),
            (u, m, h, b, t, c) -> {
              assertTrue(new String(b).contains("code_verifier="));
              return reply(
                  400, "{\"error\":\"invalid_grant\",\"error_description\":\"PKCE mismatch\"}");
            },
            (i, c, n) -> {
              throw new AssertionError("must not verify rejected grant");
            },
            clock);
    var attempt = oauth.begin("default", URI.create("http://127.0.0.1:1455/auth/callback"));
    var failure =
        assertThrows(
            ChatGptException.class,
            () ->
                oauth.complete(
                    attempt,
                    Map.of("state", attempt.state(), "code", "code", "client_id", "oaiapp_new")));
    assertEquals(ChatGptException.Code.AUTH_INVALID, failure.code());
    assertNull(store().locked(s -> s.load("default")));
  }

  @Test
  void idTokenFailureDoesNotReplaceCredentials() {
    var oauth =
        new PlanOAuth(
            store(),
            (u, m, h, b, t, c) -> reply(200, grant("access", "refresh")),
            (i, c, n) -> {
              throw ChatGptException.of(ChatGptException.Code.AUTH_INVALID);
            },
            clock);
    var attempt = oauth.begin("default", URI.create("http://127.0.0.1:1455/auth/callback"));
    assertThrows(
        ChatGptException.class,
        () ->
            oauth.complete(
                attempt,
                Map.of("state", attempt.state(), "code", "code", "client_id", "oaiapp_new")));
    assertNull(store().locked(s -> s.load("default")));
  }

  @Test
  void concurrentRefreshIsSingleFlightAcrossInstances() throws Exception {
    store()
        .locked(
            s -> {
              s.save(
                  "default",
                  new TokenSet(
                      "oaiapp_test",
                      "sub",
                      null,
                      "id",
                      "old",
                      "old-refresh",
                      Set.of("chatgpt.tokens.use.direct"),
                      clock.instant().getEpochSecond()));
              return null;
            });
    AtomicInteger refreshes = new AtomicInteger();
    PlanTransport transport =
        (u, m, h, b, t, c) -> {
          refreshes.incrementAndGet();
          assertTrue(new String(b).contains("refresh_token=old-refresh"));
          return reply(200, grant("new", "rotated"));
        };
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var jobs = new ArrayList<Future<TokenSet>>();
      for (int i = 0; i < 20; i++) {
        var oauth = new PlanOAuth(store(), transport, (id, client, n) -> null, clock);
        jobs.add(pool.submit(() -> oauth.access("default")));
      }
      for (var job : jobs) assertEquals("rotated", job.get().refreshToken());
    }
    assertEquals(1, refreshes.get());
  }

  @Test
  void terminalRefreshFailureClearsTokensButNetworkFailureKeepsThem() {
    store()
        .locked(
            s -> {
              s.save(
                  "default",
                  new TokenSet(
                      "oaiapp_test",
                      "sub",
                      null,
                      "id",
                      "old",
                      "refresh",
                      Set.of("chatgpt.tokens.use.direct"),
                      0));
              return null;
            });
    var network =
        new PlanOAuth(
            store(),
            (u, m, h, b, t, c) -> {
              throw ChatGptException.of(ChatGptException.Code.TRANSPORT);
            },
            (i, c, n) -> null,
            clock);
    assertThrows(ChatGptException.class, () -> network.access("default"));
    assertEquals("refresh", store().locked(s -> s.load("default")).refreshToken());
    var expired =
        new PlanOAuth(
            store(),
            (u, m, h, b, t, c) -> reply(400, "{\"error\":\"invalid_grant\"}"),
            (i, c, n) -> null,
            clock);
    assertThrows(ChatGptException.class, () -> expired.access("default"));
    assertNull(store().locked(s -> s.load("default")).refreshToken());
  }

  @Test
  void logoutUsesDiscoveryAndReportsUnconfirmedRevocation() {
    store()
        .locked(
            s -> {
              s.save("default", ProtectedCredentialStoreTest.tokens("access", "refresh"));
              return null;
            });
    var oauth =
        new PlanOAuth(
            store(),
            (u, m, h, b, t, c) -> {
              if (m.equals("GET"))
                return reply(
                    200,
                    "{\"revocation_endpoint\":\"https://auth.openai.com/api/accounts/oauth/revoke\"}");
              return reply(503, "{}");
            },
            (i, c, n) -> null,
            clock);
    assertFalse(oauth.logout("default"));
    assertNull(store().locked(s -> s.load("default")).accessToken());
    assertEquals("oaiapp_test", store().locked(s -> s.load("default")).clientId());
  }
}
