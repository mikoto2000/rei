package dev.mikoto2000.rei.shellautomation;

import io.micrometer.core.instrument.Metrics;

/**
 * Terminal samples have bounded labels and never include command text, paths or environment values.
 */
public final class ShellExecutionTelemetry {
  private ShellExecutionTelemetry() {}

  public static void terminal(
      String mode, String shell, String outcome, String category, boolean observed) {
    if (!java.util.Set.of("powershell", "pwsh", "cmd", "bash", "zsh", "sh").contains(shell))
      shell = "other";
    String certainty =
        observed
            ? "observed"
            : category.equals("NONE") || category.equals("UNKNOWN") ? "unknown" : "reported";
    Metrics.counter(
            "rei.shell.executions",
            "mode",
            mode,
            "shell",
            shell,
            "outcome",
            outcome,
            "category",
            category,
            "certainty",
            certainty)
        .increment();
    org.slf4j.LoggerFactory.getLogger(ShellExecutionTelemetry.class)
        .debug(
            "Shell terminal: mode={}, shell={}, outcome={}, category={}, certainty={}",
            mode,
            shell,
            outcome,
            category,
            certainty);
  }
}
