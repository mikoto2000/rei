package dev.mikoto2000.rei.subagent;

import java.time.Instant;
import java.util.List;

public record SubAgentResult(String agentId, String subAgentRunId, Status status, String output,
    Instant startedAt, Instant completedAt, SubAgentOutput structuredOutput, List<ValidationError> validationErrors,
    int repairAttempts, List<List<ValidationError>> validationHistory) {
  public SubAgentResult {
    validationErrors = List.copyOf(validationErrors);
    validationHistory = validationHistory.stream().map(List::copyOf).toList();
    if (repairAttempts < 0 || repairAttempts > 3) throw new IllegalArgumentException("Invalid repair attempts");
  }
  public SubAgentResult(String agentId, String subAgentRunId, Status status, String output,
      Instant startedAt, Instant completedAt, SubAgentOutput structuredOutput, List<ValidationError> validationErrors) {
    this(agentId, subAgentRunId, status, output, startedAt, completedAt, structuredOutput, validationErrors, 0, List.of());
  }
  public SubAgentResult(String agentId, String subAgentRunId, Status status, String output,
      Instant startedAt, Instant completedAt) {
    this(agentId, subAgentRunId, status, output, startedAt, completedAt, null, List.of());
  }
  public enum Status { COMPLETED, FAILED, MAX_STEPS_EXCEEDED, TIMEOUT, CANCELLED, UNKNOWN_AGENT }
}
