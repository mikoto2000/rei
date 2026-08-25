package dev.mikoto2000.rei.event;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

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
    return newEvent(AgentEventType.AGENT_RUN_COMPLETED, runId, null,
        new AgentRunCompletedPayload(runId, duration, completionTokens, tokensPerSecond));
  }

  public AgentEvent runFailed(String runId, ErrorInformation error) {
    return newEvent(AgentEventType.AGENT_RUN_FAILED, runId, null,
        new AgentRunFailedPayload(runId, error));
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
}
