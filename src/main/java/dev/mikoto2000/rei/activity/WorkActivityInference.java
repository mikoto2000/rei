package dev.mikoto2000.rei.activity;

import java.time.Instant;
import java.util.List;

/** Supplemental inference, never an observation interval or a claim of user execution. */
public record WorkActivityInference(String id,String inputHash,Instant inferredAt,Instant windowStart,Instant windowEnd,
    String projectId,String project,String inferredActivity,List<String> observationIds,List<Execution> executions,
    double confidence,String status,String method,int llmCalls,long totalTokens) {
  public record Execution(String eventId,Instant at,String projectId,String kind,String actor,String outcome,
      String resultSummary,String sessionId,String turnId,String runId) {}
  public WorkActivityInference {
    observationIds=List.copyOf(observationIds);executions=List.copyOf(executions);
    if(id==null || inputHash==null || inferredAt==null || windowStart==null || windowEnd==null || windowStart.isAfter(windowEnd)
        || !Double.isFinite(confidence) || confidence<0 || confidence>.8 || inferredActivity==null || inferredActivity.length()>600
        || observationIds.size()>120 || executions.size()>64 || llmCalls<0 || totalTokens<0)
      throw new IllegalArgumentException("Invalid temporal inference");
    if(executions.stream().anyMatch(e->!"REI".equals(e.actor())))throw new IllegalArgumentException("Unsupported execution actor");
  }
}
