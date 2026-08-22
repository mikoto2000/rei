package dev.mikoto2000.rei.ui.projection;

import java.time.Instant;

public record AgentRunView(
    String runId,
    AgentRunStatus status,
    Instant startedAt,
    Instant completedAt,
    Long durationMillis,
    ErrorView error) {

  public static AgentRunView idle() {
    return new AgentRunView(null, AgentRunStatus.IDLE, null, null, null, null);
  }
}
