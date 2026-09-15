package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.RunSnapshot;

public record RunResponse(String runId, String status, String sessionId, String turnId, String projectId,
    String startedAt, String completedAt, Failure failure) {
  public record Failure(String type, String message) {}
  public static RunResponse from(RunSnapshot run) {
    var context = run.context();
    return new RunResponse(context.runId(), run.status().name(), context.conversationId(), context.runId(),
        context.projectId(), run.startedAt() == null ? null : run.startedAt().toString(),
        run.completedAt() == null ? null : run.completedAt().toString(),
        run.failure() == null ? null : new Failure(run.failure().type(), run.failure().message()));
  }
}
