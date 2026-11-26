package dev.mikoto2000.rei.chatgpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A storage failure at the final admission fence must not consume account capacity forever. */
class PlanAdmissionStoreFailureTest {
  @TempDir Path root;

  @Test
  void failureWhileRecheckingTheRegistrationReturnsTheAlreadyAcquiredSlot() {
    var reads = new AtomicInteger();
    SecretProtector protection =
        new SecretProtector() {
          public byte[] protect(byte[] input) {
            return ProtectedCredentialStoreTest.TEST_PROTECTION.protect(input);
          }

          public byte[] unprotect(byte[] input) {
            if (reads.incrementAndGet() == 2)
              throw new IllegalStateException("fixture storage failure");
            return ProtectedCredentialStoreTest.TEST_PROTECTION.unprotect(input);
          }
        };
    var store = new ProtectedCredentialStore(root, protection);
    var saved = ProtectedCredentialStoreTest.tokens("access-secret", "refresh-secret");
    store.locked(tx -> {
      tx.save("personal", saved);
      return null;
    });
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          throw new AssertionError("Local admission must not dispatch HTTP");
        };
    var client =
        new PlanClient(
            new PlanOAuth(store, transport, (id, app, nonce) -> null, Clock.systemUTC()),
            transport,
            Clock.systemUTC());
    assertEquals(
        ChatGptException.Code.STORE_UNAVAILABLE,
        assertThrows(
                ChatGptException.class,
                () -> client.begin("personal", saved.registrationId(), "model"))
            .code());
    assertThatCode(
            () -> {
              try (var next = client.begin("personal", saved.registrationId(), "model")) {
                assertThat(next).isNotNull();
              }
            })
        .doesNotThrowAnyException();
    TokenSet current = store.locked(tx -> tx.load("personal"));
    assertThat(current).isEqualTo(saved);
  }
}
