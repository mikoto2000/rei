package dev.mikoto2000.rei.subagent;

import java.time.Instant;

public record SubAgentResult(String agentId, String subAgentRunId, Status status, String output,
    Instant startedAt, Instant completedAt) {
  public enum Status { COMPLETED, FAILED, MAX_STEPS_EXCEEDED, TIMEOUT, CANCELLED, UNKNOWN_AGENT }
}
