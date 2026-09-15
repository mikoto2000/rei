package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExternalAgentProcessRunnerTest {
  @TempDir Path root;
  List<String> command(String mode) {
    return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
        System.getProperty("java.class.path"), Fixture.class.getName(), mode);
  }
  ExternalAgentProcessRunner.Output run(String mode, Duration total, Duration idle, int limit, AtomicBoolean cancel) {
    return new ExternalAgentProcessRunner().run(command(mode), root, "hello", total, idle, limit, cancel::get);
  }
  @Test void capturesBothStreamsAndNonZeroExit() {
    var output = run("exit", Duration.ofSeconds(10), Duration.ofSeconds(5), 1024, new AtomicBoolean());
    assertEquals(ExternalAgentResult.Status.FAILED, output.status());
    assertEquals(7, output.exitCode());
    assertTrue(output.stdout().contains("hello"));
    assertTrue(output.stderr().contains("diagnostic"));
  }
  @Test void preservesLiteralQuotesSpacesAndBackslashesInNativeArguments() {
    List<String> arguments = List.of("permissions.rei_review={filesystem={\":minimal\"=\"read\"}}",
        "projects.\"C:/project with spaces\".trust_level=\"untrusted\"", "quote=\"x\\\"y\"", "\"trailing\\");
    List<String> invocation = new ArrayList<>(command("args"));
    invocation.addAll(arguments);
    var output = new ExternalAgentProcessRunner().run(invocation, root, "", Duration.ofSeconds(10),
        Duration.ofSeconds(5), 4096, () -> false);
    assertEquals(ExternalAgentResult.Status.SUCCESS, output.status());
    assertEquals(arguments.stream().map(s -> Base64.getEncoder().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))).toList(),
        output.stdout().lines().toList());
  }
  @Test void drainsLargeStreamsWithoutGrowingMemory() {
    var output = run("large", Duration.ofSeconds(15), Duration.ofSeconds(5), 4096, new AtomicBoolean());
    assertEquals(0, output.exitCode());
    assertTrue(output.truncated());
    assertTrue(output.stdout().length() + output.stderr().length() <= 4096);
  }
  @Test void distinguishesTimeouts() {
    assertEquals(ExternalAgentResult.Status.TOTAL_TIMEOUT,
        run("sleep", Duration.ofMillis(400), Duration.ofSeconds(10), 1024, new AtomicBoolean()).status());
    assertEquals(ExternalAgentResult.Status.INACTIVITY_TIMEOUT,
        run("sleep", Duration.ofSeconds(10), Duration.ofMillis(400), 1024, new AtomicBoolean()).status());
  }
  @Test void cancellationCleansUpChildProcesses() throws Exception {
    AtomicBoolean cancel = new AtomicBoolean();
    try (var pool = Executors.newSingleThreadExecutor()) {
      var future = pool.submit(() -> run("child", Duration.ofSeconds(15), Duration.ofSeconds(10), 4096, cancel));
      Path pid = root.resolve("child.pid");
      long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
      while (!Files.exists(pid) && System.nanoTime() < deadline) Thread.sleep(20);
      assertTrue(Files.exists(pid));
      long child = Long.parseLong(Files.readString(pid));
      cancel.set(true);
      assertEquals(ExternalAgentResult.Status.CANCELLED, future.get(5, TimeUnit.SECONDS).status());
      assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
    }
  }
  @Test void missingCommandIsFriendlyFailure() {
    var result = new ExternalAgentProcessRunner().run(List.of(root.resolve("missing-command").toString()), root,
        "", Duration.ofSeconds(1), Duration.ofSeconds(1), 1024, () -> false);
    assertEquals(ExternalAgentResult.Status.UNAVAILABLE, result.status());
    assertNull(result.exitCode());
    assertTrue(result.stderr().contains("missing-command"));
  }
  @Test void normalExitAndPeriodicActivity() {
    var result = run("activity", Duration.ofSeconds(8), Duration.ofSeconds(2), 4096, new AtomicBoolean());
    assertEquals(ExternalAgentResult.Status.SUCCESS, result.status());
    assertTrue(result.stdout().contains("tick"));
    assertFalse(result.truncated());
  }
  @Test void preCancelledDoesNotStartAndInvalidWorkingDirectoryFails() {
    assertEquals(ExternalAgentResult.Status.CANCELLED,
        run("child", Duration.ofSeconds(5), Duration.ofSeconds(5), 4096, new AtomicBoolean(true)).status());
    assertFalse(Files.exists(root.resolve("child.pid")));
    assertEquals(ExternalAgentResult.Status.UNAVAILABLE, new ExternalAgentProcessRunner().run(command("exit"),
        root.resolve("missing"), "", Duration.ofSeconds(1), Duration.ofSeconds(1), 1024, () -> false).status());
  }
  public static class Fixture {
    public static void main(String[] args) throws Exception {
      switch (args[0]) {
        case "args" -> { for (int i = 1; i < args.length; i++) System.out.println(Base64.getEncoder().encodeToString(args[i].getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        case "activity" -> { for (int i = 0; i < 8; i++) { System.out.println("tick"); Thread.sleep(250); } }
        case "exit" -> { System.out.println(new String(System.in.readAllBytes())); System.err.println("diagnostic"); System.exit(7); }
        case "large" -> {
          Thread err = Thread.ofPlatform().start(() -> { for (int i = 0; i < 10000; i++) System.err.println("e".repeat(1000)); });
          for (int i = 0; i < 10000; i++) System.out.println("o".repeat(1000));
          err.join();
        }
        case "child" -> {
          Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
              "-cp", System.getProperty("java.class.path"), Fixture.class.getName(), "sleep").start();
          Files.writeString(Path.of("child.pid"), Long.toString(child.pid()));
          Thread.sleep(60000);
        }
        default -> Thread.sleep(60000);
      }
    }
  }
}
