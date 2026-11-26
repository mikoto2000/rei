package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A lease and model catalog belong to the persisted account incarnation. */
class PlanAccountBindingTest {
  @TempDir Path root;

  private ProtectedCredentialStore store() {
    return new ProtectedCredentialStore(root, ProtectedCredentialStoreTest.TEST_PROTECTION);
  }

  private void register(String access) {
    store()
        .locked(s -> {
          s.save("personal", ProtectedCredentialStoreTest.tokens(access, "refresh-secret"));
          return null;
        });
  }

  private PlanOAuth oauth(PlanTransport transport) {
    return new PlanOAuth(store(), transport, (id, client, nonce) -> null, Clock.systemUTC());
  }

  @Test
  void replacingCredentialsForTheSameClientAndSubjectInvalidatesAnExistingLease() {
    register("first-access");
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) ->
            PlanOAuthTest.reply(200, PlanClientTest.MODELS);
    var client = new PlanClient(oauth(transport), transport, Clock.systemUTC());
    var old = client.lease("personal", "allowed");
    assertTrue(old.valid().getAsBoolean());
    register("second-access");
    assertFalse(old.valid().getAsBoolean(), "Old credentials must not survive account replacement");
    assertTrue(client.lease("personal", "allowed").valid().getAsBoolean());
    assertFalse(old.valid().getAsBoolean(), "A new lease must not resurrect the old one");
  }

  @Test
  void directOAuthLogoutInvalidatesTheLeaseWithoutACommandSpecificStopCall() {
    register("first-access");
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          if (uri.getPath().endsWith("/models"))
            return PlanOAuthTest.reply(200, PlanClientTest.MODELS);
          if (method.equals("GET"))
            return PlanOAuthTest.reply(
                200, "{\"revocation_endpoint\":\"https://auth.openai.com/revoke\"}");
          return PlanOAuthTest.reply(200, "");
        };
    var oauth = oauth(transport);
    var client = new PlanClient(oauth, transport, Clock.systemUTC());
    var old = client.lease("personal", "allowed");
    assertTrue(old.valid().getAsBoolean());
    assertTrue(oauth.logout("personal"));
    assertFalse(old.valid().getAsBoolean(), "All callers must observe account logout");
  }

  @Test
  void aNewRegistrationCannotReuseThePreviousAccountCatalog() {
    register("first-access");
    var queries = new AtomicInteger();
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          int number = queries.incrementAndGet();
          assertEquals(
              number == 1 ? "Bearer first-access" : "Bearer second-access",
              headers.get("Authorization"));
          String model = number == 1 ? "first-model" : "second-model";
          return PlanOAuthTest.reply(
              200,
              "{\"models\":[{\"slug\":\""
                  + model
                  + "\",\"display_name\":\"Visible\",\"visibility\":\"list\"}]}");
        };
    var client = new PlanClient(oauth(transport), transport, Clock.systemUTC());
    assertEquals(
        List.of("first-model"),
        client.models("personal", false).stream().map(PlanClient.Model::id).toList());
    register("second-access");
    assertEquals(
        List.of("second-model"),
        client.models("personal", false).stream().map(PlanClient.Model::id).toList());
    assertEquals(2, queries.get());
  }

  private PlanClient changingAccountDuringCatalog(boolean logout) {
    var current = new AtomicReference<PlanOAuth>();
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          if (uri.getPath().endsWith("/models")) {
            if (logout) assertTrue(current.get().logout("personal"));
            else register("second-access");
            return PlanOAuthTest.reply(200, PlanClientTest.MODELS);
          }
          if (method.equals("GET"))
            return PlanOAuthTest.reply(
                200, "{\"revocation_endpoint\":\"https://auth.openai.com/revoke\"}");
          return PlanOAuthTest.reply(200, "");
        };
    current.set(oauth(transport));
    return new PlanClient(current.get(), transport, Clock.systemUTC());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void catalogResponseCannotBePublishedAfterItsRegistrationChanges(boolean logout) {
    register("first-access");
    var client = changingAccountDuringCatalog(logout);
    assertEquals(
        ChatGptException.Code.AUTH_REQUIRED,
        assertThrows(ChatGptException.class, () -> client.models("personal", false)).code());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void leaseCannotMixCatalogAndCredentialsFromDifferentRegistrations(boolean logout) {
    register("first-access");
    var client = changingAccountDuringCatalog(logout);
    assertEquals(
        ChatGptException.Code.AUTH_REQUIRED,
        assertThrows(ChatGptException.class, () -> client.lease("personal", "allowed")).code());
  }
}
