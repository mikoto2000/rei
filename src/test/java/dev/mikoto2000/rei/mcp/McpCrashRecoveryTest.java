package dev.mikoto2000.rei.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.conversation.ConversationAdmissionStore;
import dev.mikoto2000.rei.conversation.SqliteConversationTurnStore;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class McpCrashRecoveryTest {
  @TempDir Path root;

  @Test
  void forciblyKilledOwnerKeepsCompletedSnapshotAndMarksPendingUnknownWithoutResubmission()
      throws Exception {
    String executable =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
            .toString();
    Path arguments = root.resolve("worker.args");
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
            .replace('\\', '/');
    Files.writeString(
        arguments,
        "-cp\n\""
            + classpath
            + "\"\n"
            + McpCrashWorker.class.getName()
            + "\n\""
            + root.toString().replace('\\', '/')
            + "\"\n");
    var process = new ProcessBuilder(executable, "@" + arguments).redirectErrorStream(true).start();
    try {
      var ready =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                try (var reader =
                    new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream()))) {
                  String line;
                  while ((line = reader.readLine()) != null) if (line.equals("READY")) return true;
                  return false;
                } catch (java.io.IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
              });
      assertTrue(ready.get(15, TimeUnit.SECONDS));
      process.destroyForcibly();
      assertTrue(process.waitFor(5, TimeUnit.SECONDS));
      try (var gate = new StorageMigrationCoordinator(root)) {
        gate.prepare();
        try (var admissions = new ConversationAdmissionStore(root, Clock.systemUTC())) {
          var store =
              new McpRequestStore(
                  root,
                  admissions,
                  new SqliteConversationTurnStore(root),
                  Clock.systemUTC(),
                  Duration.ofDays(30),
                  Duration.ofDays(90));
          assertEquals(RunStatus.UNKNOWN, admissions.get("pending").orElseThrow().status());
          assertNotNull(
              admissions.get("pending").orElseThrow().startedAt(),
              "The killed owner must have started the Run");
          assertEquals(RunStatus.COMPLETED, admissions.get("completed").orElseThrow().status());
          assertTrue(store.byRequest(ConversationAdmissionStore.hash("pending")).isPresent());
          assertTrue(
              store.byRun("completed").orElseThrow().result().contains("immutable saved result"));
          assertEquals("COMPLETED", store.byRun("completed").orElseThrow().state());
        }
      }
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }
}
