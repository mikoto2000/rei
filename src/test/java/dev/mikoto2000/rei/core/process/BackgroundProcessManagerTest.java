package dev.mikoto2000.rei.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mikoto2000.rei.core.service.SystemShellService;

class BackgroundProcessManagerTest {
  private final BackgroundProcessManager manager = new BackgroundProcessManager(new SystemShellService());

  @TempDir
  Path tempDir;

  @AfterEach
  void tearDown() {
    manager.shutdown();
  }

  @Test
  void spawnReturnsImmediatelyAndCapturesOutput() throws Exception {
    long started = System.nanoTime();

    BackgroundProcessSnapshot spawned = manager.spawnCommandLine(javaCommand("run"), tempDir);

    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
    assertTrue(elapsedMillis < 1000);
    assertNotNull(spawned.processId());
    assertTrue(spawned.pid() > 0);
    assertEquals(BackgroundProcessStatus.RUNNING, spawned.status());

    BackgroundProcessSnapshot actual = awaitStdout(spawned.processId(), "ready");
    assertEquals(BackgroundProcessStatus.RUNNING, actual.status());
    assertTrue(actual.stderr().contains("err-ready"));
  }

  @Test
  void statusReportsExitedProcessAndExitCode() throws Exception {
    BackgroundProcessSnapshot spawned = manager.spawnCommandLine(javaCommand("exit", "7"), tempDir);

    BackgroundProcessSnapshot actual = awaitStatus(spawned.processId(), BackgroundProcessStatus.EXITED);

    assertEquals(7, actual.exitCode());
    assertTrue(actual.stdout().contains("fixture stdout"));
    assertTrue(actual.stderr().contains("fixture stderr"));
  }

  @Test
  void killTerminatesManagedProcessOnly() throws Exception {
    BackgroundProcessSnapshot spawned = manager.spawnCommandLine(javaCommand("run"), tempDir);
    awaitStdout(spawned.processId(), "ready");

    BackgroundProcessSnapshot killed = manager.kill(spawned.processId());
    BackgroundProcessSnapshot actual = awaitStatus(spawned.processId(), BackgroundProcessStatus.KILLED);

    assertEquals(BackgroundProcessStatus.KILLED, killed.status());
    assertEquals(BackgroundProcessStatus.KILLED, actual.status());
    assertTrue(killed.message().contains("killed"));
  }

  @Test
  void killReturnsNotFoundForUnknownProcess() {
    BackgroundProcessSnapshot actual = manager.kill("proc-missing");

    assertEquals(BackgroundProcessStatus.FAILED, actual.status());
    assertEquals(false, actual.found());
  }

  @Test
  void managesMultipleProcessesIndependently() throws Exception {
    BackgroundProcessSnapshot first = manager.spawnCommandLine(javaCommand("run"), tempDir);
    BackgroundProcessSnapshot second = manager.spawnCommandLine(javaCommand("run"), tempDir);
    awaitStdout(first.processId(), "ready");
    awaitStdout(second.processId(), "ready");

    manager.kill(first.processId());
    BackgroundProcessSnapshot killed = awaitStatus(first.processId(), BackgroundProcessStatus.KILLED);
    BackgroundProcessSnapshot stillRunning = manager.status(second.processId(), 100);

    assertEquals(BackgroundProcessStatus.KILLED, killed.status());
    assertEquals(BackgroundProcessStatus.RUNNING, stillRunning.status());
  }

  private BackgroundProcessSnapshot awaitStdout(String processId, String expectedLine) throws Exception {
    long deadline = System.currentTimeMillis() + 5000;
    BackgroundProcessSnapshot snapshot;
    do {
      snapshot = manager.status(processId, 100);
      if (snapshot.stdout().contains(expectedLine)) {
        return snapshot;
      }
      Thread.sleep(50);
    } while (System.currentTimeMillis() < deadline);
    return snapshot;
  }

  private BackgroundProcessSnapshot awaitStatus(String processId, BackgroundProcessStatus expectedStatus)
      throws Exception {
    long deadline = System.currentTimeMillis() + 5000;
    BackgroundProcessSnapshot snapshot;
    do {
      snapshot = manager.status(processId, 100);
      if (snapshot.status() == expectedStatus) {
        return snapshot;
      }
      Thread.sleep(50);
    } while (System.currentTimeMillis() < deadline);
    return snapshot;
  }

  private List<String> javaCommand(String... args) {
    String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
    List<String> command = new java.util.ArrayList<>();
    command.add(java);
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(LongRunningProcessFixture.class.getName());
    command.addAll(List.of(args));
    return command;
  }

  private boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }
}
