package dev.mikoto2000.rei.timing;

import java.time.Instant;
import java.util.*;

/** Metadata-only local timing; optional metrics never use text length as token counts. */
public interface TimingRecorder {
  enum Category { LLM, TOOL, SEARCH, APPROVAL_WAIT, RETRY, COMPLETION_VALIDATION, OTHER }
  enum Status { SUCCESS, FAILED, CANCELLED, TIMED_OUT, DISCONNECTED, INCOMPLETE }
  enum Metric { FIRST_COMMUNICATION, FIRST_FRAMEWORK_CHUNK, FIRST_GENERATED_TOKEN, FIRST_GENERATION_TEXT, FIRST_VISIBLE_OUTPUT }
  record Span(String id,String parentId,String requestId,String attemptId,Category category,Status status,
      Instant startedAt,long startNanos,Long endNanos,Map<Metric,Long> milestones,
      Long inputTokens,Long outputTokens,Long generatedTokens,Long generationNanos) {
    public Span {milestones=Map.copyOf(milestones);}
    /** Only an explicit real generated count and matching measured/provider duration qualify. */
    public Double generationTps() {
      return generatedTokens==null||generationNanos==null||generationNanos<=0?null:generatedTokens/(generationNanos/1_000_000_000d);
    }
  }
  record Summary(long workNanos,long occupiedNanos,Map<Category,Long> occupancyNanos,
      long overlapNanos,long crossCategoryOverlapNanos,long unmeasuredNanos) {
    public Summary {occupancyNanos=Map.copyOf(occupancyNanos);}
  }
  record Run(String id,String projectId,String sessionId,Status status,Instant startedAt,long elapsedNanos,
      boolean incomplete,List<Span> spans,Map<Metric,Long> milestones,Summary summary) {
    public Run {spans=List.copyOf(spans);milestones=Map.copyOf(milestones);}
  }
  record Statistics(int runs,int spans,long evictedRuns,long droppedSpans) {}
  boolean enabled();
  boolean beginRun(String runId,String projectId,String sessionId);
  boolean beginSpan(String runId,String spanId,String parentId,String requestId,String attemptId,Category category);
  boolean endSpan(String runId,String spanId,Status status);
  boolean finishRun(String runId,Status status);
  void markSpan(String runId,String spanId,Metric metric);
  void markRun(String runId,Metric metric);
  void usage(String runId,String spanId,Long inputTokens,Long outputTokens,Long generatedTokens,Long generationNanos);
  Optional<Run> snapshot(String projectId,String sessionId,String runId);
  Optional<Run> latest(String projectId,String sessionId);
  Statistics statistics();
}
