package dev.mikoto2000.rei.core.execution;

import java.time.Instant;

public record CompletedExecution(String id,String projectId,String conversationId,ExecutionType type,
    String source,String result,Instant startedAt,Instant completedAt) {
  public static CompletedExecution of(ActiveExecution execution,String source,String result,Instant completedAt) {
    return new CompletedExecution(execution.id(),execution.projectId(),execution.conversationId(),execution.type(),source,result,execution.startedAt(),completedAt);
  }
}
