package dev.mikoto2000.rei.shellautomation;

import java.nio.file.Path;

/** Shared automation-only launch contract. Interactive /sh is intentionally separate. */
public final class ProcessLaunchService {
  private final java.util.function.BiFunction<String, String, java.util.List<String>> commandLines;
  private final ShellEnvironmentService environment;

  public record Launch(ProcessBuilder builder, ShellExecutionObservation observation) {}

  public ProcessLaunchService(
      java.util.function.BiFunction<java.util.Map<String, String>, String, String> resolver,
      java.util.function.BiFunction<String, String, java.util.List<String>> commandLines) {
    this.commandLines = commandLines;
    this.environment = new ShellEnvironmentService(resolver, commandLines);
  }

  public ShellEnvironmentService environment() {
    return environment;
  }

  public static void closeInput(Process process) {
    try {
      process.getOutputStream().close();
    } catch (java.io.IOException failure) {
      org.slf4j.LoggerFactory.getLogger(ProcessLaunchService.class)
          .debug("Could not close automation stdin: {}", failure.getClass().getSimpleName());
    }
  }

  public Launch prepare(String command, Path directory) {
    var detected = environment.current();
    String script = command;
    if (detected.shell().equals("powershell") || detected.shell().equals("pwsh")) {
      String checked =
          command
              + "\n$reiLastCommandSucceeded = $?\n"
              + "if (-not $reiLastCommandSucceeded) { if ($LASTEXITCODE) { exit $LASTEXITCODE }; exit 1 }\n";
      String payload =
          java.util.Base64.getEncoder()
              .encodeToString(checked.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
      script =
          "[Console]::InputEncoding = [Text.UTF8Encoding]::new($false)\n"
              + "[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)\n"
              + "$OutputEncoding = [Console]::OutputEncoding\n"
              + "$ErrorActionPreference = 'Stop'\n"
              + "$ProgressPreference = 'SilentlyContinue'\n"
              // Native exit codes remain explicit: tools such as rg use nonzero for normal
              // outcomes.
              + "if (Test-Path variable:PSNativeCommandUseErrorActionPreference) { $PSNativeCommandUseErrorActionPreference = $false }\n"
              // Parse user text only AFTER console encoding is configured. PowerShell 5.1
              // otherwise emits localized syntax errors in its legacy console encoding.
              + "try { $reiAutomationScript = [ScriptBlock]::Create([Text.Encoding]::Unicode.GetString([Convert]::FromBase64String('"
              + payload
              + "'))) } catch { [Console]::Error.WriteLine('ParserError: ' + $_.Exception.Message); exit 1 }\n"
              + "& $reiAutomationScript\n";
    }
    var builder = new ProcessBuilder(commandLines.apply(detected.executable(), script));
    builder.directory(directory.toFile());
    return new Launch(builder, new ShellExecutionObservation(detected, "prepared", false, null));
  }
}
