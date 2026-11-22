package dev.mikoto2000.rei.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.shellautomation.ProcessOutputCapture;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellEnvironmentServiceTest {
  @TempDir Path directory;

  @Test
  void versionProbeHasADeadlineAndTerminatesItsOwnedProcess() throws Exception {
    Path executable = java.nio.file.Files.createFile(directory.resolve("powershell.exe"));
    Path pid = directory.resolve("probe.pid");
    var environment =
        new dev.mikoto2000.rei.shellautomation.ShellEnvironmentService(
            (values, os) -> executable.toString(),
            (selected, script) ->
                dev.mikoto2000.rei.testsupport.JavaFixtureCommand.command(
                    directory, SlowProbe.class, pid.toString()));
    long started = System.nanoTime();
    assertEquals(
        "timed-out", environment.inspect(Map.of(), System.getProperty("os.name")).probeStatus());
    assertTrue(java.time.Duration.ofNanos(System.nanoTime() - started).toSeconds() < 6);
    long ownedPid = Long.parseLong(java.nio.file.Files.readString(pid));
    var process = ProcessHandle.of(ownedPid);
    if (process.isPresent()) process.get().onExit().get(3, java.util.concurrent.TimeUnit.SECONDS);
    assertTrue(
        ProcessHandle.of(ownedPid).isEmpty()
            || !ProcessHandle.of(ownedPid).orElseThrow().isAlive());
  }

  public static class SlowProbe {
    public static void main(String[] args) throws Exception {
      java.nio.file.Files.writeString(
          Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
      Thread.sleep(30000);
    }
  }

  @Test
  void detectsMissingExplicitShellAndInvalidatesCacheOnSelectionChange() {
    var environment = new SystemShellService().automation().environment();
    var selection = Map.of("SHELL", directory.resolve("missing.exe").toString());
    var missing = environment.inspect(selection, "Windows 11");
    assertEquals("not-found", missing.probeStatus());
    assertSame(missing, environment.inspect(selection, "Windows 11"));
    var changed =
        environment.inspect(
            Map.of("SHELL", directory.resolve("another.exe").toString()), "Windows 11");
    assertNotEquals(missing.executable(), changed.executable());
    environment.invalidate();
    assertNotSame(changed, environment.inspect(selection, "Windows 11"));
  }

  @Test
  void neverSearchesCurrentDirectoryOrExposesFullEnvironment() {
    var environment = new SystemShellService().automation().environment();
    assertEquals(
        "not-found",
        environment
            .inspect(
                Map.of("SHELL", "unique-missing-shell", "PATH", "", "TOKEN", "secret"), "Windows")
            .probeStatus());
    assertThrows(
        IllegalArgumentException.class,
        () -> environment.findExecutables(List.of("git; echo hello")));
    assertThrows(
        IllegalArgumentException.class, () -> environment.findExecutables(List.of("../git")));
    assertThrows(
        IllegalArgumentException.class,
        () -> environment.findExecutables(java.util.Collections.nCopies(17, "git")));
  }

  @Test
  void captureBoundsOutputAndReportsInvalidEncodingAndReadFailures() {
    var output =
        ProcessOutputCapture.read(
            new java.io.ByteArrayInputStream(
                "abcdefghijkl".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            4);
    assertEquals("abcd", output.text());
    assertTrue(output.truncated());
    assertNull(output.readError());
    var invalid =
        ProcessOutputCapture.read(new java.io.ByteArrayInputStream(new byte[] {(byte) 0xff}), 100);
    assertNotNull(invalid.readError());
    var failed =
        ProcessOutputCapture.read(
            new java.io.InputStream() {
              @Override
              public int read() throws java.io.IOException {
                throw new java.io.IOException("secret");
              }
            },
            100);
    assertEquals("IOException", failed.readError());
  }
}
