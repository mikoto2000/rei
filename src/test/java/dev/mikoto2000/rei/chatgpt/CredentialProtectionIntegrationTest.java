package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** 認証情報保護外部連携の結果と状態の契約を検証するテスト。 */
class CredentialProtectionIntegrationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** ChatGPTとの連携で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void productionDpapiRoundTripsAndDoesNotStorePlaintext() {
    var store = new ProtectedCredentialStore(root, new WindowsDpapiProtector());
    store.locked(
        s -> {
          s.save(
              "account",
              ProtectedCredentialStoreTest.tokens("dpapi-access-secret", "dpapi-refresh-secret"));
          return null;
        });
    assertEquals("dpapi-access-secret", store.locked(s -> s.load("account")).accessToken());
    assertDoesNotThrow(
        () -> {
          assertFalse(
              new String(
                      Files.readAllBytes(root.resolve("credentials.bin")),
                      java.nio.charset.StandardCharsets.ISO_8859_1)
                  .contains("dpapi-access-secret"));
        });
  }

  /** ChatGPTとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void fileLockSerializesSeparateProcesses() throws Exception {
    var store = new ProtectedCredentialStore(root, ProtectedCredentialStoreTest.TEST_PROTECTION);
    store.locked(
        s -> {
          s.save("default", ProtectedCredentialStoreTest.tokens("0", "refresh"));
          return null;
        });
    String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String cp = System.getProperty("java.class.path");
    var argsFile = root.resolve("worker.args");
    Files.writeString(
        argsFile,
        "-cp\n\""
            + cp.replace("\\", "\\\\")
            + "\"\n"
            + Worker.class.getName()
            + "\n\""
            + root.toString().replace("\\", "\\\\")
            + "\"\n");
    var processes = new ArrayList<Process>();
    for (int i = 0; i < 2; i++)
      processes.add(
          new ProcessBuilder(javaBinary, "@" + argsFile)
              .redirectError(root.resolve("worker-" + i + ".err").toFile())
              .redirectOutput(root.resolve("worker-" + i + ".out").toFile())
              .start());
    try {
      for (var process : processes) {
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
      }
    } finally {
      for (var process : processes) if (process.isAlive()) process.destroyForcibly();
    }
    assertEquals("40", store.locked(s -> s.load("default")).accessToken());
  }

  /** 検証条件を再現するための実行の担当。 */
  public static class Worker {
    /** 検証用に、chatGPTとの連携の起動要求を受け付け、実行を開始する。 */
    public static void main(String[] args) {
      var store =
          new ProtectedCredentialStore(
              Path.of(args[0]), ProtectedCredentialStoreTest.TEST_PROTECTION);
      for (int i = 0; i < 20; i++)
        store.locked(
            s -> {
              int previous = Integer.parseInt(s.load("default").accessToken());
              s.save(
                  "default",
                  ProtectedCredentialStoreTest.tokens(Integer.toString(previous + 1), "rotated"));
              return null;
            });
    }
  }
}
