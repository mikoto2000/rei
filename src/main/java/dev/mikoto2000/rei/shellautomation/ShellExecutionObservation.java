package dev.mikoto2000.rei.shellautomation;

/** Facts recorded by Rei, separate from interpretations of untrusted command output. */
public record ShellExecutionObservation(
    ShellEnvironmentService.Environment environment,
    String launchStatus,
    boolean outputTruncated,
    String outputReadError) {
  public ShellExecutionObservation output(boolean truncated, String error) {
    return new ShellExecutionObservation(environment, launchStatus, truncated, error);
  }
}
