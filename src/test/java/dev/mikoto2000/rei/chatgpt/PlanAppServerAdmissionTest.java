package dev.mikoto2000.rei.chatgpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Only the app-server session is observable here; its internal HTTP is not claimed as controlled.
 */
class PlanAppServerAdmissionTest {
  @TempDir Path root;

  private PlanClient accounts() {
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          assertThat(method).isEqualTo("GET");
          return PlanOAuthTest.reply(200, PlanClientTest.MODELS);
        };
    var store = new ProtectedCredentialStore(root, ProtectedCredentialStoreTest.TEST_PROTECTION);
    store.locked(tx -> {
      tx.save("default", ProtectedCredentialStoreTest.tokens("access", "refresh"));
      return null;
    });
    return new PlanClient(
        new PlanOAuth(store, transport, (id, app, nonce) -> null, Clock.systemUTC()),
        transport,
        Clock.systemUTC());
  }

  private PlanSseAssembler.Result infer(PlanAppServerClient client) {
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "session", root, "project"))) {
      return client.inferHistory(
          "default",
          "allowed",
          "fixture instructions",
          List.of(
              new PlanClient.Input(
                  "user",
                  "{\"sources\":[{\"path\":\"docs/source.md\",\"content\":\"approved\"}]}")),
          Duration.ofSeconds(2),
          4096,
          4096,
          () -> false,
          () -> {});
    }
  }

  @Test
  void occupiedAccountCapacityRejectsBeforeStartingAnExternalSession() {
    var accounts = accounts();
    var starts = new AtomicInteger();
    var client =
        new PlanAppServerClient(
            accounts,
            (tokens, timeout, cancelled) -> {
              starts.incrementAndGet();
              throw new AssertionError("An occupied account must not start an external session");
            },
            mock(ToolPermissionGuard.class));
    try (var parent = accounts.begin("default", accounts.registration("default"), "allowed")) {
      assertThat(parent).isNotNull();
      assertEquals(
          ChatGptException.Code.BUSY,
          assertThrows(ChatGptException.class, () -> infer(client)).code());
      assertThat(starts).hasValue(0);
    }
  }

  @Test
  void observableSessionHoldsSharedCapacityAndReturnsItAfterCompletion() {
    var accounts = accounts();
    var registration = accounts.registration("default");
    var session =
        new PlanAppServerRpcTest.Fake(
            PlanAppServerRpcTest.USAGE, PlanAppServerRpcTest.done("completed"));
    var frames = new java.util.ArrayList<>(session.incoming);
    frames.set(
        2, PlanAppServerRpcTest.THREAD.replace("\"model\":\"model\"", "\"model\":\"allowed\""));
    session.incoming.clear();
    session.incoming.addAll(frames);
    var client =
        new PlanAppServerClient(
            accounts,
            (tokens, timeout, cancelled) -> {
              assertEquals(
                  ChatGptException.Code.BUSY,
                  assertThrows(
                          ChatGptException.class,
                          () -> accounts.begin("default", registration, "allowed"))
                      .code());
              return new PlanAppServerClient.Started(session, "/isolated/workspace");
            },
            mock(ToolPermissionGuard.class));
    assertThat(infer(client).state()).isEqualTo(PlanSseAssembler.State.COMPLETED);
    assertThat(session.closed).isTrue();
    try (var child = accounts.begin("default", registration, "allowed")) {
      assertThat(child.invalidated().toCompletableFuture()).isNotDone();
    }
  }
}
