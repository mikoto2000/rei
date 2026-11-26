package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Registration identity is persisted separately from rotating credentials. */
class PlanRegistrationTest {
  @TempDir Path root;
  private final ObjectMapper json = new ObjectMapper();

  private ProtectedCredentialStore store() {
    return new ProtectedCredentialStore(root, ProtectedCredentialStoreTest.TEST_PROTECTION);
  }

  private String registration(TokenSet tokens) {
    String value = json.valueToTree(tokens).path("registrationId").asText("");
    assertFalse(value.isBlank(), "A saved registration needs a persistent nonsecret identity");
    return value;
  }

  @Test
  void registrationSurvivesReopeningAndLogoutButNotReplacementOrAnotherLabel() {
    store()
        .locked(s -> {
          s.save(
              "personal", ProtectedCredentialStoreTest.tokens("access-secret", "refresh-secret"));
          s.save("work", ProtectedCredentialStoreTest.tokens("access-secret", "refresh-secret"));
          return null;
        });
    String initial = registration(store().locked(s -> s.load("personal")));
    assertEquals(initial, registration(store().locked(s -> s.load("personal"))));
    assertNotEquals(initial, registration(store().locked(s -> s.load("work"))));
    store()
        .locked(s -> {
          s.save("personal", s.load("personal").withoutTokens());
          return null;
        });
    assertEquals(initial, registration(store().locked(s -> s.load("personal"))));
    store()
        .locked(s -> {
          s.save("personal", ProtectedCredentialStoreTest.tokens("new-access", "new-refresh"));
          return null;
        });
    assertNotEquals(initial, registration(store().locked(s -> s.load("personal"))));
  }

  @Test
  void tokenRefreshPreservesRegistrationAcrossStoreInstances() {
    Clock clock = Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC);
    store()
        .locked(s -> {
          s.save(
              "personal",
              new TokenSet(
                  "oaiapp_test",
                  "subject",
                  null,
                  "id",
                  "old-access",
                  "old-refresh",
                  Set.of("chatgpt.tokens.use.direct"),
                  0));
          return null;
        });
    String initial = registration(store().locked(s -> s.load("personal")));
    var refreshes = new AtomicInteger();
    var oauth =
        new PlanOAuth(
            store(),
            (uri, method, headers, body, timeout, cancelled) -> {
              assertEquals(PlanOAuth.TOKEN, uri);
              assertEquals("POST", method);
              refreshes.incrementAndGet();
              return PlanOAuthTest.reply(
                  200,
                  "{\"token_type\":\"Bearer\",\"access_token\":\"new-access\","
                      + "\"refresh_token\":\"new-refresh\",\"expires_in\":3600}");
            },
            (id, client, nonce) -> null,
            clock);
    assertEquals("new-access", oauth.access("personal").accessToken());
    assertEquals(initial, registration(oauth.access("personal")));
    assertEquals(initial, registration(store().locked(s -> s.load("personal"))));
    assertEquals(1, refreshes.get());
  }

  @Test
  void oldProtectedRecordIsMigratedOnceWithoutChangingTokens() throws Exception {
    var old =
        json.valueToTree(ProtectedCredentialStoreTest.tokens("access-secret", "refresh-secret"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) old).remove("registrationId");
    Files.write(
        root.resolve("credentials.bin"),
        ProtectedCredentialStoreTest.TEST_PROTECTION.protect(
            json.writeValueAsBytes(Map.of("personal", old))));
    TokenSet first = store().locked(s -> s.load("personal"));
    String initial = registration(first);
    assertEquals("access-secret", first.accessToken());
    assertEquals("refresh-secret", first.refreshToken());
    assertEquals(initial, registration(store().locked(s -> s.load("personal"))));
    byte[] protectedBytes = Files.readAllBytes(root.resolve("credentials.bin"));
    var migrated =
        json.readTree(ProtectedCredentialStoreTest.TEST_PROTECTION.unprotect(protectedBytes));
    assertEquals(initial, migrated.path("personal").path("registrationId").asText());
    assertFalse(
        new String(protectedBytes, java.nio.charset.StandardCharsets.UTF_8)
            .contains("access-secret"));
  }
}
