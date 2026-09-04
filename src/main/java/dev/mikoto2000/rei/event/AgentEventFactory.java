package dev.mikoto2000.rei.event;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.topic.IdleTriggerRejectReason;
import dev.mikoto2000.rei.topic.TopicGenerationStage;
import dev.mikoto2000.rei.topic.TopicRejectionReason;
import dev.mikoto2000.rei.topic.TopicScoreBreakdown;
import dev.mikoto2000.rei.topic.TopicSpeakSkipReason;

/**
 * Agent Event を作成する Factory。
 *
 * <p>呼び出し側が大量の boilerplate を書かず、イベント内容に集中できる API を提供する。
 * 各メソッドは Envelope の共通属性（id / timestamp / version など）を自動的に埋める。</p>
 */
@Component
public class AgentEventFactory {

  private static final int VERSION = 1;

  private final Clock clock;

  public AgentEventFactory(Clock clock) {
    this.clock = clock;
  }

  // ---- Agent Run ----

  public AgentEvent runStarted(String runId, String reason, String parentRunId) {
    return newEvent(AgentEventType.AGENT_RUN_STARTED, runId, null,
        new AgentRunStartedPayload(runId, reason, parentRunId));
  }

  public AgentEvent runCompleted(String runId, long duration) {
    return runCompleted(runId, duration, null);
  }

  public AgentEvent runCompleted(String runId, long duration, Long completionTokens) {
    return runCompleted(runId, duration, completionTokens, null);
  }

  public AgentEvent runCompleted(String runId, long duration, Long completionTokens, Double tokensPerSecond) {
    return runCompleted(runId, duration, completionTokens, null, tokensPerSecond, null);
  }

  public AgentEvent runCompleted(String runId, long duration, Long completionTokens, Double timeToFirstTokenMillis,
      Double outputTokensPerSecond, Double endToEndTokensPerSecond) {
    return newEvent(AgentEventType.AGENT_RUN_COMPLETED, runId, null,
        new AgentRunCompletedPayload(runId, duration, completionTokens, timeToFirstTokenMillis,
            outputTokensPerSecond, endToEndTokensPerSecond));
  }

  public AgentEvent runFailed(String runId, ErrorInformation error) {
    return newEvent(AgentEventType.AGENT_RUN_FAILED, runId, null,
        new AgentRunFailedPayload(runId, error));
  }

  // ---- LLM ----

  public AgentEvent llmRequestStarted(String runId, String requestId, String feature) {
    return newEvent(AgentEventType.LLM_REQUEST_STARTED, runId, requestId,
        new LlmRequestStartedPayload(requestId, feature));
  }

  public AgentEvent llmResponseCompleted(String runId, String requestId, long durationMs) {
    return newEvent(AgentEventType.LLM_RESPONSE_COMPLETED, runId, requestId,
        new LlmResponseCompletedPayload(requestId, durationMs));
  }

  // ---- Message ----

  public AgentEvent messageStarted(String messageId, String role) {
    return newEvent(AgentEventType.MESSAGE_STARTED, null, null,
        new MessageStartedPayload(messageId, role));
  }

  public AgentEvent messageDelta(String messageId, String delta) {
    return newEvent(AgentEventType.MESSAGE_DELTA, null, null,
        new MessageDeltaPayload(messageId, delta));
  }

  public AgentEvent messageCompleted(String messageId, String role, String text) {
    return newEvent(AgentEventType.MESSAGE_COMPLETED, null, null,
        new MessageCompletedPayload(messageId, role, text));
  }

  public AgentEvent memoryConsolidationSuggested() {
    return newEvent(AgentEventType.MEMORY_CONSOLIDATION_SUGGESTED, null, null,
        new MemoryConsolidationSuggestedPayload());
  }

  // ---- Thinking ----

  public AgentEvent thinkingStarted(String thinkingId) {
    return newEvent(AgentEventType.THINKING_STARTED, null, null,
        new ThinkingStartedPayload(thinkingId));
  }

  public AgentEvent thinkingDelta(String thinkingId, String delta) {
    return newEvent(AgentEventType.THINKING_DELTA, null, null,
        new ThinkingDeltaPayload(thinkingId, delta));
  }

  public AgentEvent thinkingCompleted(String thinkingId, String text) {
    return newEvent(AgentEventType.THINKING_COMPLETED, null, null,
        new ThinkingCompletedPayload(thinkingId, text));
  }

  // ---- Tool ----

  public AgentEvent toolStarted(String toolCallId, String toolName, String argumentsSummary) {
    return newEvent(AgentEventType.TOOL_STARTED, null, toolCallId,
        new ToolStartedPayload(toolCallId, toolName, argumentsSummary, null));
  }

  public AgentEvent toolStarted(String toolCallId, String toolName, String argumentsSummary, String summary) {
    return newEvent(AgentEventType.TOOL_STARTED, null, toolCallId,
        new ToolStartedPayload(toolCallId, toolName, argumentsSummary, summary));
  }

  public AgentEvent toolCompleted(String toolCallId, String toolName, long duration, String resultSummary) {
    return newEvent(AgentEventType.TOOL_COMPLETED, null, toolCallId,
        new ToolCompletedPayload(toolCallId, toolName, duration, resultSummary, null, null, null, null));
  }

  public AgentEvent toolCompleted(String toolCallId, String toolName, long duration, String resultSummary,
      Integer files, Long bytes, Integer matches, Integer items) {
    return newEvent(AgentEventType.TOOL_COMPLETED, null, toolCallId,
        new ToolCompletedPayload(toolCallId, toolName, duration, resultSummary, files, bytes, matches, items));
  }

  public AgentEvent toolFailed(String toolCallId, String toolName, ErrorInformation error) {
    return newEvent(AgentEventType.TOOL_FAILED, null, toolCallId,
        new ToolFailedPayload(toolCallId, toolName, error));
  }

  // ---- Skill selection ----

  public AgentEvent skillSelectionStarted(String selectionId) {
    return newEvent(AgentEventType.SKILL_SELECTION_STARTED, null, selectionId,
        new SkillSelectionStartedPayload(selectionId));
  }

  public AgentEvent skillSelectionCompleted(String selectionId, java.util.List<String> explicitSkillNames,
      java.util.List<String> implicitSkillNames, java.util.List<String> warnings) {
    return newEvent(AgentEventType.SKILL_SELECTION_COMPLETED, null, selectionId,
        new SkillSelectionCompletedPayload(selectionId, explicitSkillNames, implicitSkillNames, warnings));
  }

  public AgentEvent skillSelectionFailed(String selectionId, ErrorInformation error) {
    return newEvent(AgentEventType.SKILL_SELECTION_FAILED, null, selectionId,
        new SkillSelectionFailedPayload(selectionId, error));
  }

  public AgentEvent skillRoutingStarted(String runId, String routingId, int candidateCount,
      int routingInvocation) {
    return newEvent(AgentEventType.SKILL_ROUTING_STARTED, runId, routingId,
        new SkillRoutingStartedPayload(candidateCount, routingInvocation));
  }

  public AgentEvent skillRoutingCompleted(String runId, String routingId, long durationMs, int candidateCount,
      String selectedSkill, int routingInvocation, Long selectorDurationMs, Long metadataLoadDurationMs,
      Long skillLoadDurationMs, java.util.List<String> explicitSkillNames,
      java.util.List<String> implicitSkillNames, java.util.List<String> warnings) {
    return newEvent(AgentEventType.SKILL_ROUTING_COMPLETED, runId, routingId,
        new SkillRoutingCompletedPayload(durationMs, candidateCount, selectedSkill, routingInvocation,
            selectorDurationMs, metadataLoadDurationMs, skillLoadDurationMs, explicitSkillNames,
            implicitSkillNames, warnings));
  }

  public AgentEvent skillRoutingFailed(String runId, String routingId, long durationMs, int candidateCount,
      int routingInvocation, ErrorInformation error) {
    return newEvent(AgentEventType.SKILL_ROUTING_FAILED, runId, routingId,
        new SkillRoutingFailedPayload(durationMs, candidateCount, routingInvocation, error));
  }

  public AgentEvent skillCandidatesEvaluated(String runId, String routingId, int totalSkillCount,
      long durationMs, String actualSelectedSkill, boolean selected, Boolean top1Hit, Boolean top3Hit,
      Boolean top5Hit, java.util.List<SkillCandidatesEvaluatedPayload.CandidateScore> topCandidates) {
    return newEvent(AgentEventType.SKILL_CANDIDATES_EVALUATED, runId, routingId,
        new SkillCandidatesEvaluatedPayload(totalSkillCount, topCandidates.size(), durationMs, actualSelectedSkill,
            selected, top1Hit, top3Hit, top5Hit, topCandidates));
  }

  // ---- Task ----

  public AgentEvent taskCreated(String taskId, String parentTaskId, String title, String status) {
    return newEvent(AgentEventType.TASK_CREATED, null, null,
        new TaskCreatedPayload(taskId, parentTaskId, title, status));
  }

  public AgentEvent taskStarted(String taskId) {
    return newEvent(AgentEventType.TASK_STARTED, null, null,
        new TaskStartedPayload(taskId));
  }

  public AgentEvent taskCompleted(String taskId, Long duration) {
    return newEvent(AgentEventType.TASK_COMPLETED, null, null,
        new TaskCompletedPayload(taskId, duration));
  }

  public AgentEvent taskFailed(String taskId, ErrorInformation error) {
    return newEvent(AgentEventType.TASK_FAILED, null, null,
        new TaskFailedPayload(taskId, error));
  }

  // ---- Working Set ----

  public AgentEvent workingSetItemAdded(String itemId, String kind, String identifier, String path, String reason) {
    return workingSetItemAdded(itemId, kind, identifier, path, reason, null);
  }

  public AgentEvent workingSetItemAdded(String itemId, String kind, String identifier, String path, String reason,
      String correlationId) {
    return newEvent(AgentEventType.WORKING_SET_ITEM_ADDED, null, correlationId,
        new WorkingSetItemAddedPayload(itemId, kind, identifier, path, reason));
  }

  public AgentEvent workingSetItemRemoved(String itemId, String reason) {
    return newEvent(AgentEventType.WORKING_SET_ITEM_REMOVED, null, null,
        new WorkingSetItemRemovedPayload(itemId, reason));
  }

  public AgentEvent workingSetSearchStarted(String searchId, String query, String strategy,
      int workingSetSizeBefore) {
    return newEvent(AgentEventType.WORKING_SET_SEARCH_STARTED, null, searchId,
        new WorkingSetSearchStartedPayload(searchId, query, strategy, workingSetSizeBefore));
  }

  public AgentEvent workingSetSearchCompleted(String searchId, long durationMs, int hitCount,
      int candidateCount, int selectedCount, int alreadyPresentCount, int workingSetSizeBefore,
      int workingSetSizeAfter) {
    return newEvent(AgentEventType.WORKING_SET_SEARCH_COMPLETED, null, searchId,
        new WorkingSetSearchCompletedPayload(searchId, durationMs, hitCount, candidateCount, selectedCount,
            alreadyPresentCount, workingSetSizeBefore, workingSetSizeAfter));
  }

  // ---- Topic Generator ----

  public AgentEvent topicGenerationStarted(String runId, String topicGenerationId, String trigger) {
    Instant now = Instant.now(clock);
    return newEvent(AgentEventType.TOPIC_GENERATION_STARTED, runId, topicGenerationId,
        new TopicGenerationStartedPayload(topicGenerationId, bounded(trigger, 80), now));
  }

  public AgentEvent topicIdleTriggerEvaluated(IdleTriggerRejectReason reason, boolean accepted,
      Duration idleDuration, Duration requiredIdle) {
    Instant now = Instant.now(clock);
    return newEvent(AgentEventType.TOPIC_IDLE_TRIGGER_EVALUATED, null, null,
        new TopicIdleTriggerEvaluatedPayload(accepted, millis(idleDuration), millis(requiredIdle), reason, now));
  }

  public AgentEvent topicCandidatesRefreshed(int candidateCount) {
    Instant now = Instant.now(clock);
    return newEvent(AgentEventType.TOPIC_CANDIDATES_REFRESHED, null, null,
        new TopicCandidatesRefreshedPayload(candidateCount, now));
  }

  public AgentEvent topicCandidateGenerated(String runId, String topicGenerationId, String topicCandidateId,
      String topicType, String source, String topic, String reason, Double priority, Double freshness,
      Double usefulness, Double intrusiveness, Double confidence) {
    return newEvent(AgentEventType.TOPIC_CANDIDATE_GENERATED, runId, topicGenerationId,
        new TopicCandidateGeneratedPayload(topicGenerationId, topicCandidateId, topicType, source, bounded(topic, 160),
            bounded(reason, 240), priority, freshness, usefulness, intrusiveness, confidence));
  }

  public AgentEvent topicCandidateScored(String runId, String topicGenerationId, String topicCandidateId,
      TopicScoreBreakdown score) {
    return newEvent(AgentEventType.TOPIC_CANDIDATE_SCORED, runId, topicGenerationId,
        new TopicCandidateScoredPayload(topicGenerationId, topicCandidateId, score));
  }

  public AgentEvent topicCandidateRejected(String runId, String topicGenerationId, String topicCandidateId,
      TopicRejectionReason reason, Double score) {
    return newEvent(AgentEventType.TOPIC_CANDIDATE_REJECTED, runId, topicGenerationId,
        new TopicCandidateRejectedPayload(topicGenerationId, topicCandidateId, reason, score));
  }

  public AgentEvent topicSelected(String runId, String topicGenerationId, String topicCandidateId,
      Double score, Integer rank) {
    return newEvent(AgentEventType.TOPIC_SELECTED, runId, topicGenerationId,
        new TopicSelectedPayload(topicGenerationId, topicCandidateId, score, rank));
  }

  public AgentEvent topicSpoken(String runId, String topicGenerationId, String topicCandidateId, String messageId,
      Instant spokenAt, String message) {
    return newEvent(AgentEventType.TOPIC_SPOKEN, runId, topicGenerationId,
        new TopicSpokenPayload(topicGenerationId, topicCandidateId, messageId, spokenAt, bounded(message, 240)));
  }

  public AgentEvent topicSpeakSkipped(String runId, String topicGenerationId, String topicCandidateId,
      TopicSpeakSkipReason reason, Instant nextSpeakAllowedAt) {
    return newEvent(AgentEventType.TOPIC_SPEAK_SKIPPED, runId, topicGenerationId,
        new TopicSpeakSkippedPayload(topicGenerationId, topicCandidateId, reason, nextSpeakAllowedAt));
  }

  public AgentEvent topicGenerationCompleted(String runId, String topicGenerationId, int candidateCount,
      int scoredCount, int rejectedCount, String selectedCandidateId, boolean spoken, long durationMs) {
    Instant now = Instant.now(clock);
    return newEvent(AgentEventType.TOPIC_GENERATION_COMPLETED, runId, topicGenerationId,
        new TopicGenerationCompletedPayload(topicGenerationId, candidateCount, scoredCount, rejectedCount,
            selectedCandidateId, spoken, durationMs, now));
  }

  public AgentEvent topicGenerationFailed(String runId, String topicGenerationId, TopicGenerationStage stage,
      Throwable error) {
    Instant now = Instant.now(clock);
    String message = error == null ? "unknown error" : error.getClass().getSimpleName() + ": " + error.getMessage();
    return newEvent(AgentEventType.TOPIC_GENERATION_FAILED, runId, topicGenerationId,
        new TopicGenerationFailedPayload(topicGenerationId, stage, bounded(message, 240), now));
  }

  public AgentEvent topicAutoSpeakSuppressed(String reason) {
    Instant now = Instant.now(clock);
    return newEvent(AgentEventType.TOPIC_AUTO_SPEAK_SUPPRESSED, null, null,
        new TopicAutoSpeakSuppressedPayload(bounded(reason, 160), now));
  }

  // ---- Context ----

  public AgentEvent contextSnapshotUpdated(Long usedTokens, Long maxTokens, Double utilization) {
    return newEvent(AgentEventType.CONTEXT_SNAPSHOT_UPDATED, null, null,
        new ContextSnapshotUpdatedPayload(usedTokens, maxTokens, utilization));
  }

  // ---- File ----

  public AgentEvent fileCreated(String path) {
    return newEvent(AgentEventType.FILE_CREATED, null, null,
        new FileCreatedPayload(path));
  }

  public AgentEvent fileModified(String path, Integer addedLines, Integer removedLines) {
    return newEvent(AgentEventType.FILE_MODIFIED, null, null,
        new FileModifiedPayload(path, addedLines, removedLines));
  }

  public AgentEvent fileDeleted(String path) {
    return newEvent(AgentEventType.FILE_DELETED, null, null,
        new FileDeletedPayload(path));
  }

  // ---- 共通 ----

  private AgentEvent newEvent(AgentEventType type, String runId, String correlationId, AgentEventPayload payload) {
    return new AgentEvent(
        UUID.randomUUID().toString(),
        0L,
        Instant.now(clock),
        type,
        VERSION,
        null,
        null,
        runId,
        correlationId,
        null,
        payload);
  }

  private String bounded(String value, int maxLength) {
    if (value == null) return null;
    String safe = value
        .replaceAll("(?i)((?:api[_-]?key|access[_-]?token|token|password|secret|credential)\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|\\S+)", "$1[REDACTED]")
        .replaceAll("[\\p{Cntrl}]+", " ")
        .replaceAll("\\s+", " ")
        .trim();
    return safe.length() <= maxLength ? safe : safe.substring(0, maxLength - 1) + "…";
  }

  private long millis(Duration duration) {
    return duration == null ? 0L : Math.max(0L, duration.toMillis());
  }
}
