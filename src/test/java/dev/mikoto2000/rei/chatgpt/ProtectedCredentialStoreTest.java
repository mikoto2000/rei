package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProtectedCredentialStoreTest {
  @TempDir Path root;
  static final SecretProtector TEST_PROTECTION =
      new SecretProtector() {
        public byte[] protect(byte[] input) {
          byte[] copy = input.clone();
          for (int i = 0; i < copy.length; i++) copy[i] ^= 0x55;
          return copy;
        }

        public byte[] unprotect(byte[] input) {
          return protect(input);
        }
      };

  static TokenSet tokens(String access, String refresh) {
    return new TokenSet(
        "oaiapp_test",
        "subject",
        null,
        "id-secret",
        access,
        refresh,
        Set.of("chatgpt.tokens.use.direct"),
        Instant.now().plusSeconds(3600).getEpochSecond());
  }

  @Test
  void atomicallyStoresProtectedTokensAndRetainsRegistrationOnLogout() throws Exception {
    var store = new ProtectedCredentialStore(root, TEST_PROTECTION);
    store.locked(
        s -> {
          s.save("default", tokens("access-secret", "refresh-secret"));
          return null;
        });
    assertFalse(Files.readString(root.resolve("credentials.bin")).contains("access-secret"));
    assertEquals("access-secret", store.locked(s -> s.load("default")).accessToken());
    store.locked(
        s -> {
          s.save("default", s.load("default").withoutTokens());
          return null;
        });
    assertEquals("oaiapp_test", store.locked(s -> s.load("default")).clientId());
    assertNull(store.locked(s -> s.load("default")).refreshToken());
    assertFalse(tokens("access-secret", "refresh-secret").toString().contains("secret"));
  }

  @Test
  void hostIsStableAcrossStoreInstances() throws Exception {
    var first = new ProtectedCredentialStore(root, TEST_PROTECTION);
    var second = new ProtectedCredentialStore(root, TEST_PROTECTION);
    String firstId = first.locked(s -> s.hostId());
    String secondId = second.locked(s -> s.hostId());
    assertEquals(firstId, secondId);
  }

  @Test
  void concurrentInstancesSerializeReadModifyWrite() throws Exception {
    var first = new ProtectedCredentialStore(root, TEST_PROTECTION);
    var second = new ProtectedCredentialStore(root, TEST_PROTECTION);
    first.locked(
        s -> {
          s.save("default", tokens("0", "0"));
          return null;
        });
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var jobs = new ArrayList<Future<?>>();
      for (int i = 0; i < 20; i++) {
        var store = i % 2 == 0 ? first : second;
        jobs.add(
            pool.submit(
                () ->
                    store.locked(
                        s -> {
                          var old = s.load("default");
                          s.save(
                              "default",
                              tokens(
                                  Integer.toString(Integer.parseInt(old.accessToken()) + 1),
                                  "rotated"));
                          return null;
                        })));
      }
      for (var job : jobs) job.get();
    }
    assertEquals("20", first.locked(s -> s.load("default")).accessToken());
  }

  @Test
  void protectionFailureNeverWritesPlaintext() {
    var store =
        new ProtectedCredentialStore(
            root,
            new SecretProtector() {
              public byte[] protect(byte[] b) {
                throw new IllegalStateException("secret");
              }

              public byte[] unprotect(byte[] b) {
                throw new IllegalStateException();
              }
            });
    var error =
        assertThrows(
            ChatGptException.class,
            () ->
                store.locked(
                    s -> {
                      s.save("default", tokens("access-secret", "refresh-secret"));
                      return null;
                    }));
    assertFalse(error.toString().contains("secret"));
    assertFalse(Files.exists(root.resolve("credentials.bin")));
  }
}
