package dev.mikoto2000.rei.application.run;

import java.time.Instant;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

public record RunSnapshot(AgentRunContext context, RunStatus status, Instant startedAt,
    Instant completedAt, RunFailure failure, ChildOrigin childOrigin) {
  public record ChildOrigin(String parentRunId,String sessionId,String agentId) {}
  public RunSnapshot(AgentRunContext context,RunStatus status,Instant startedAt,Instant completedAt,RunFailure failure) {
    this(context,status,startedAt,completedAt,failure,null);
  }
}
