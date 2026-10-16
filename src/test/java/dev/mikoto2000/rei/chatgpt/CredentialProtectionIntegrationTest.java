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

class CredentialProtectionIntegrationTest {
  @TempDir Path root;

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

  public static class Worker {
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
