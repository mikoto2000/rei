package dev.mikoto2000.rei.externalagent;

import java.util.List;

public record ExternalAgentResult(Status status, String summary, List<ExternalAgentFinding> findings,
    List<String> warnings, long duration, Integer exitCode, String rawOutput) {
  public enum Status { SUCCESS, SUCCESS_WITH_WARNINGS, FAILED, UNAVAILABLE, TOTAL_TIMEOUT, INACTIVITY_TIMEOUT, CANCELLED, REJECTED }
  public ExternalAgentResult { findings = List.copyOf(findings); warnings = List.copyOf(warnings); }
  public boolean success() { return status == Status.SUCCESS || status == Status.SUCCESS_WITH_WARNINGS; }
  public static ExternalAgentResult rejected(String reason) {
    return new ExternalAgentResult(Status.REJECTED, reason, List.of(), List.of(), 0, null, "");
  }
  /** Only this bounded review, never process logs, is passed into rei's ephemeral reasoning. */
  public ExternalAgentResult forEvaluation() {
    return new ExternalAgentResult(status, summary, findings, warnings, duration, exitCode, "");
  }
}
