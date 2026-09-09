package dev.mikoto2000.rei.core.execution;

import java.nio.file.Path;
import java.time.Instant;
import dev.mikoto2000.rei.core.chat.ActiveRun;

public record ActiveExecution(String id,String projectId,String conversationId,Path projectRoot,
    ExecutionType type,String summary,Instant startedAt) {
  public String status() { return "RUNNING"; }
  public static ActiveExecution fromAgent(ActiveRun run) {
    return new ActiveExecution(run.runId(),run.projectId(),run.conversationId(),run.projectRoot(),ExecutionType.AGENT,run.requestSummary(),run.startedAt());
  }
}
