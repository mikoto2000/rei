package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 保護された認証情報保存先の結果と状態の契約を検証するテスト。 */
class ProtectedCredentialStoreTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使う検証保護。 */
  static final SecretProtector TEST_PROTECTION =
      new SecretProtector() {
        /** {@code protect} の条件を再現するための補助情報を返す。 */
        public byte[] protect(byte[] input) {
          byte[] copy = input.clone();
          for (int i = 0; i < copy.length; i++) copy[i] ^= 0x55;
          return copy;
        }

        /** {@code unprotect} の条件を再現するための補助情報を返す。 */
        public byte[] unprotect(byte[] input) {
          return protect(input);
        }
      };

  /** {@code tokens} の条件を再現するための補助情報を返す。 */
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

  /** 保護されたトークン数を保存することを確認する。 */
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

  /** ホスト変わらない複数にまたがるについて、起動単位一覧を保存することを確認する。 */
  @Test
  void hostIsStableAcrossStoreInstances() throws Exception {
    var first = new ProtectedCredentialStore(root, TEST_PROTECTION);
    var second = new ProtectedCredentialStore(root, TEST_PROTECTION);
    String firstId = first.locked(s -> s.hostId());
    String secondId = second.locked(s -> s.hostId());
    assertEquals(firstId, secondId);
  }

  /** ChatGPTとの連携で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
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

  /** ChatGPTとの連携で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void protectionFailureNeverWritesPlaintext() {
    var store =
        new ProtectedCredentialStore(
            root,
            new SecretProtector() {
              /** {@code protect} の条件を再現するための補助情報を返す。 */
              public byte[] protect(byte[] b) {
                throw new IllegalStateException("secret");
              }

              /** {@code unprotect} の条件を再現するための補助情報を返す。 */
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
