package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.process.BackgroundProcessManager;
import dev.mikoto2000.rei.core.process.BackgroundProcessStatus;
import dev.mikoto2000.rei.core.process.RunCommandRequest;
import dev.mikoto2000.rei.core.process.RunCommandResult;
import dev.mikoto2000.rei.core.service.SystemShellService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
@EnabledOnOs(OS.WINDOWS)
class WindowsShellExecutionTest {
  @TempDir Path directory;

  private SystemShellService selected(String shell) {
    return new SystemShellService() {
      @Override
      public String resolveShell(Map<String, String> environment, String os) {
        return shell;
      }
    };
  }

  @Test
  void actualWindowsPowershellAndPwshUseSameContractInAllModes() throws Exception {
    directory = java.nio.file.Files.createDirectory(directory.resolve("作業 空白"));
    for (String shell : List.of("powershell", "pwsh")) {
      var service = selected(shell);
      assertEquals(
          "available",
          service.automation().environment().current().probeStatus(),
          "Required Windows shell: " + shell);
      assertTrue(Path.of(service.automation().environment().current().executable()).isAbsolute());
      assertNotEquals("unknown", service.automation().environment().current().version());
      var manager = new BackgroundProcessManager(service);
      var tools = new Tools(null, service, manager);
      try {
        for (String mode : List.of("foreground", "auto", "background")) {
          var location = finish(tools, manager, "[Console]::WriteLine((Get-Location).Path)", mode);
          assertTrue(location.stdout().contains(directory.toString()), location.toString());
          var output =
              finish(
                  tools,
                  manager,
                  "[Console]::WriteLine('日本語; quoted value'); [Console]::Error.WriteLine('標準エラー'); exit 7",
                  mode);
          assertEquals(7, output.exitCode());
          assertTrue(output.stdout().contains("日本語; quoted value"), output.toString());
          assertTrue(output.stderr().contains("標準エラー"), output.toString());
          assertEquals("started", output.execution().launchStatus());
          assertNull(output.execution().outputReadError());
          var failed =
              finish(
                  tools,
                  manager,
                  "Write-Error 'expected failure'; [Console]::WriteLine('must-not-run')",
                  mode);
          assertNotEquals(0, failed.exitCode());
          assertFalse(failed.stdout().contains("must-not-run"));
          var missing = finish(tools, manager, "rei_missing_command_abc123", mode);
          assertEquals("COMMAND_NOT_FOUND", missing.diagnosis().category(), missing.toString());
          var syntax = finish(tools, manager, "if (", mode);
          assertEquals("SHELL_PARSE_ERROR", syntax.diagnosis().category(), syntax.toString());
          var input =
              finish(
                  tools, manager, "Read-Host 'input'; [Console]::WriteLine('must-not-run')", mode);
          assertNotEquals(0, input.exitCode());
          var nativeExit = finish(tools, manager, "cmd.exe /d /c exit 9", mode);
          assertEquals(9, nativeExit.exitCode());
          var invalidOutput =
              finish(
                  tools, manager, "[Console]::OpenStandardOutput().WriteByte(255); exit 0", mode);
          assertEquals("OUTPUT_READ_FAILURE", invalidOutput.diagnosis().category());
          assertEquals("UNKNOWN", invalidOutput.diagnosis().outcome());
        }
        var large =
            finish(tools, manager, "[Console]::WriteLine(('x' * 300000)); exit 0", "foreground");
        assertTrue(large.execution().outputTruncated());
        assertTrue(large.diagnosis().partial());
        assertTrue(large.stdout().length() < 60000);
        var managedLarge =
            finish(tools, manager, "[Console]::WriteLine(('x' * 300000)); exit 0", "background");
        assertTrue(managedLarge.execution().outputTruncated());
        assertTrue(managedLarge.diagnosis().partial());
        var timeout =
            tools.runCommand(
                new RunCommandRequest("Start-Sleep 10", "foreground", 1),
                directory,
                Duration.ofMillis(1));
        assertTrue(timeout.timedOut());
        assertEquals("TIMED_OUT", timeout.diagnosis().outcome());
        assertFalse(
            tools
                .getShellEnvironment(List.of("cmd.exe"))
                .executables()
                .get("cmd.exe")
                .equals("not-found"));
      } finally {
        manager.shutdown();
      }
    }
  }

  private dev.mikoto2000.rei.core.process.RunCommandResult finish(
      Tools tools, BackgroundProcessManager manager, String command, String mode) throws Exception {
    var result =
        tools.runCommand(new RunCommandRequest(command, mode, 15), directory, Duration.ofMillis(1));
    if (!result.status().equals("running")) return result;
    var completed = manager.await(result.processId(), Duration.ofSeconds(15));
    assertNotEquals(BackgroundProcessStatus.RUNNING, completed.status());
    return new RunCommandResult(
        completed.exitCode() == 0 ? "completed" : "failed",
        mode,
        "background",
        completed.exitCode(),
        String.join("\n", completed.stdout()),
        String.join("\n", completed.stderr()),
        completed.processId(),
        completed.pid(),
        false,
        completed.message(),
        completed.execution());
  }

  @Test
  void invalidWorkingDirectoryIsAnObservedStartFailureInEveryMode() throws Exception {
    var service = selected("powershell");
    var manager = new BackgroundProcessManager(service);
    var tools = new Tools(null, service, manager);
    try {
      for (String mode : List.of("foreground", "auto", "background")) {
        var failure =
            tools.runCommand(
                new RunCommandRequest("exit 0", mode, 5),
                directory.resolve("missing"),
                Duration.ofMillis(1));
        assertEquals("failed", failure.status());
        assertEquals("PROCESS_START_FAILURE", failure.diagnosis().category());
        assertTrue(
            failure.diagnosis().evidence().stream().anyMatch(e -> e.source().equals("execution")));
      }
    } finally {
      manager.shutdown();
    }
  }

  @Test
  void managedTerminalSamplesAreNotCountedAgainByRepeatedObservation() throws Exception {
    var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    io.micrometer.core.instrument.Metrics.addRegistry(registry);
    var service = selected("powershell");
    var manager = new BackgroundProcessManager(service);
    var tools = new Tools(null, service, manager);
    try {
      for (String mode : List.of("foreground", "auto", "background")) {
        var result = finish(tools, manager, "exit 0", mode);
        assertEquals(0, result.exitCode());
        if (result.processId() != null) {
          manager.status(result.processId(), 1);
          manager.await(result.processId(), Duration.ofSeconds(1));
        }
        assertEquals(
            1.0,
            registry
                .get("rei.shell.executions")
                .tag("mode", mode)
                .tag("shell", "powershell")
                .tag("outcome", "SUCCEEDED")
                .counter()
                .count());
      }
      assertTrue(
          registry.getMeters().stream()
              .flatMap(m -> m.getId().getTags().stream())
              .noneMatch(t ->
                  t.getValue().contains(directory.toString()) || t.getValue().contains("exit 0")));
    } finally {
      manager.shutdown();
      io.micrometer.core.instrument.Metrics.removeRegistry(registry);
      registry.close();
    }
  }
}
