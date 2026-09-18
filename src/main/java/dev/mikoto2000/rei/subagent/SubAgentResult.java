package dev.mikoto2000.rei.subagent;

import java.time.Instant;
import java.util.List;

public record SubAgentResult(String agentId, String subAgentRunId, Status status, String output,
    Instant startedAt, Instant completedAt, SubAgentOutput structuredOutput, List<ValidationError> validationErrors) {
  public SubAgentResult { validationErrors = List.copyOf(validationErrors); }
  public SubAgentResult(String agentId, String subAgentRunId, Status status, String output,
      Instant startedAt, Instant completedAt) {
    this(agentId, subAgentRunId, status, output, startedAt, completedAt, null, List.of());
  }
  public enum Status { COMPLETED, FAILED, MAX_STEPS_EXCEEDED, TIMEOUT, CANCELLED, UNKNOWN_AGENT }
}
