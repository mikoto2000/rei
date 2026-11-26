package dev.mikoto2000.rei.chatgpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Legacy direct inference and common chat share the same account request capacity. */
class PlanSharedInferenceAdmissionTest {
  @TempDir Path root;
  private final AtomicInteger dispatched = new AtomicInteger();
  private final AtomicInteger posts = new AtomicInteger();
  private final AtomicReference<PlanClient> client = new AtomicReference<>();
  private final AtomicReference<String> registration = new AtomicReference<>();

  private PlanClient accounts(Runnable duringInference) {
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          if (method.equals("GET")) return PlanOAuthTest.reply(200, PlanClientTest.MODELS);
          posts.incrementAndGet();
          duringInference.run();
          return new PlanTransport.Response(
              200,
              PlanSseAssemblerTest.stream(
                  PlanSseAssemblerTest.event("response.completed", PlanSseAssemblerTest.COMPLETED)),
              "fixture",
              "text/event-stream");
        };
    var store = new ProtectedCredentialStore(root, ProtectedCredentialStoreTest.TEST_PROTECTION);
    store.locked(tx -> {
      tx.save("default", ProtectedCredentialStoreTest.tokens("access", "refresh"));
      return null;
    });
    var oauth = new PlanOAuth(store, transport, (id, app, nonce) -> null, Clock.systemUTC());
    registration.set(oauth.registration("default"));
    var accounts = new PlanClient(oauth, transport, Clock.systemUTC());
    client.set(accounts);
    return accounts;
  }

  private PlanSseAssembler.Result infer(PlanClient accounts) {
    return accounts.inferHistory(
        "default",
        "allowed",
        "fixture instructions",
        java.util.List.of(new PlanClient.Input("user", "fixture question")),
        Duration.ofSeconds(2),
        262144,
        1048576,
        () -> false,
        dispatched::incrementAndGet);
  }

  @Test
  void aCommonChatAdmissionPreventsLegacyInferenceBeforeDispatch() {
    var accounts = accounts(() -> {});
    try (var parent = accounts.begin("default", registration.get(), "allowed")) {
      assertThat(parent).isNotNull();
      assertEquals(
          ChatGptException.Code.BUSY,
          assertThrows(ChatGptException.class, () -> infer(accounts)).code());
      assertThat(dispatched).hasValue(0);
      assertThat(posts).hasValue(0);
      assertThat(accounts.models("default", true)).hasSize(1);
    }
    assertThat(infer(accounts).text()).isEqualTo("hello");
    assertThat(posts).hasValue(1);
  }

  @Test
  void legacyInferenceOccupiesCapacityOnlyUntilItsResponseCompletes() {
    var accounts =
        accounts(() ->
            assertEquals(
                ChatGptException.Code.BUSY,
                assertThrows(
                        ChatGptException.class,
                        () -> client.get().begin("default", registration.get(), "allowed"))
                    .code()));
    assertThat(infer(accounts).text()).isEqualTo("hello");
    assertThat(dispatched).hasValue(1);
    try (var child = accounts.begin("default", registration.get(), "allowed")) {
      assertThat(child.invalidated().toCompletableFuture()).isNotDone();
    }
  }
}
