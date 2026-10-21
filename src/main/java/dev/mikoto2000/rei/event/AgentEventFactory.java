package dev.mikoto2000.rei.event;

import dev.mikoto2000.rei.topic.IdleTriggerRejectReason;
import dev.mikoto2000.rei.topic.TopicGenerationStage;
import dev.mikoto2000.rei.topic.TopicRejectionReason;
import dev.mikoto2000.rei.topic.TopicScoreBreakdown;
import dev.mikoto2000.rei.topic.TopicSpeakSkipReason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Agent Event を作成する Factory。
 *
 * <p>呼び出し側が大量の boilerplate を書かず、イベント内容に集中できる API を提供する。 各メソッドは Envelope の共通属性（id / timestamp /
 * version など）を自動的に埋める。
 */
@Component
public class AgentEventFactory {

  /** 形式のバージョン。 */
  private static final int VERSION = 1;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 指定された値や利用先を使って、AgentEventFactoryを初期化する。 */
  public AgentEventFactory(Clock clock) {
    this.clock = clock;
  }

  // ---- Agent Run ----

  /** 実行状況の通知で用いる記憶を返す。 */
  public AgentEvent memory(
      AgentEventType type, String project, String session, MemoryLifecyclePayload payload) {
    if (type != AgentEventType.AUTO_SLEEP_STOPPED
        && type != AgentEventType.MEMORY_SLEEP_STARTED
        && type != AgentEventType.MEMORY_SLEEP_COMPLETED
        && type != AgentEventType.MEMORY_SLEEP_FAILED
        && type != AgentEventType.MEMORY_RETRIEVAL_COMPLETED)
      throw new IllegalArgumentException("Not a memory event");
    return new AgentEvent(
        UUID.randomUUID().toString(),
        0L,
        Instant.now(clock),
        type,
        VERSION,
        session,
        null,
        null,
        payload.sleepRunId(),
        null,
        payload,
        project);
  }

  /** 実行状況の通知で用いるアプリケーション終了開始を返す。 */
  public AgentEvent applicationShutdownStarted(String reason) {
    // Do not inherit the run/execution scope of the thread initiating shutdown.
    return new AgentEvent(
        UUID.randomUUID().toString(),
        0L,
        Instant.now(clock),
        AgentEventType.APPLICATION_SHUTDOWN_STARTED,
        VERSION,
        null,
        null,
        null,
        null,
        null,
        new ApplicationShutdownStartedPayload(reason));
  }

  /** 実行状況の通知で用いる文脈圧縮を返す。 */
  public AgentEvent contextCompression(AgentEventType type, ContextCompressionPayload payload) {
    if (type != AgentEventType.CONTEXT_COMPRESSION_STARTED
        && type != AgentEventType.CONTEXT_COMPRESSION_COMPLETED
        && type != AgentEventType.CONTEXT_COMPRESSION_FAILED
        && type != AgentEventType.CONTEXT_COMPRESSION_SKIPPED)
      throw new IllegalArgumentException("Not a compression event");
    return newEvent(type, null, null, payload);
  }

  /** 実行状況の通知で用いる委譲を返す。 */
  public AgentEvent delegation(
      AgentEventType type, String runId, ExternalAgentLifecyclePayload payload) {
    if (type != AgentEventType.DELEGATION_STARTED
        && type != AgentEventType.DELEGATION_COMPLETED
        && type != AgentEventType.DELEGATION_FAILED
        && type != AgentEventType.DELEGATION_CANCELLED)
      throw new IllegalArgumentException("Not an external delegation event");
    return newEvent(type, runId, payload.delegationId(), payload);
  }

  /** 実行状況の通知で用いる画面操作利用進捗を返す。 */
  public AgentEvent computerUseProgress(dev.mikoto2000.rei.computeruse.ComputerProgress progress) {
    return newEvent(
        AgentEventType.COMPUTER_USE_PROGRESS,
        null,
        null,
        new ComputerUseProgressPayload(
            progress.step(),
            progress.phase(),
            progress.action(),
            progress.target(),
            progress.x(),
            progress.y(),
            progress.confidence(),
            progress.reason()));
  }

  /** 実行状況の通知で用いる補助エージェント受付から終了までの管理を返す。 */
  public AgentEvent subAgentLifecycle(
      AgentEventType type,
      String parentRunId,
      String subAgentRunId,
      String agentId,
      String task,
      String status,
      long duration,
      String failureReason) {
    if (type != AgentEventType.SUBAGENT_STARTED
        && type != AgentEventType.SUBAGENT_COMPLETED
        && type != AgentEventType.SUBAGENT_FAILED)
      throw new IllegalArgumentException("Not a SubAgent lifecycle event");
    return newEvent(
        type,
        subAgentRunId,
        parentRunId,
        new SubAgentLifecyclePayload(
            parentRunId,
            subAgentRunId,
            bounded(agentId, 64),
            bounded(task, 120),
            status,
            duration,
            bounded(failureReason, 120)));
  }

  /** 実行状況の通知で用いる補助エージェント受付から終了までの管理を返す。 */
  public AgentEvent subAgentLifecycle(
      AgentEventType type,
      String parentRunId,
      String subAgentRunId,
      String agentId,
      String task,
      String status,
      long duration,
      String failureReason,
      String backendId,
      String modelId,
      Integer totalTokens) {
    var base =
        subAgentLifecycle(
            type, parentRunId, subAgentRunId, agentId, task, status, duration, failureReason);
    var original = (SubAgentLifecyclePayload) base.payload();
    var payload =
        new SubAgentLifecyclePayload(
            original.parentRunId(),
            original.subAgentRunId(),
            original.agentId(),
            original.taskSummary(),
            original.status(),
            original.duration(),
            subAgentFailureReason(failureReason),
            backendId,
            modelId,
            totalTokens);
    return new AgentEvent(
        base.id(),
        base.sequence(),
        base.timestamp(),
        base.type(),
        base.version(),
        base.sessionId(),
        base.turnId(),
        base.runId(),
        base.correlationId(),
        base.parentEventId(),
        payload,
        base.projectId());
  }

  /**
   * 実行状況の通知で用いる補助エージェント失敗情報理由を返す。
   *
   * <p>Keep a bounded, redacted diagnostic identical in external child receipts and notifications.
   */
  public String subAgentFailureReason(String reason) {
    return bounded(reason, 1000);
  }

  /** 実行状況の通知で用いる履歴検索完了を返す。 */
  public AgentEvent historySearchCompleted(HistorySearchCompletedPayload payload) {
    var event = newEvent(AgentEventType.HISTORY_SEARCH_COMPLETED, null, null, payload);
    return new AgentEvent(
        event.id(),
        event.sequence(),
        event.timestamp(),
        event.type(),
        event.version(),
        event.sessionId(),
        event.turnId(),
        event.runId(),
        event.correlationId(),
        event.parentEventId(),
        event.payload(),
        event.projectId() == null ? payload.preferredProjectId() : event.projectId());
  }

  /** 実行状況の通知で用いる実行進捗を返す。 */
  public AgentEvent executionProgress(
      AgentEventType type, String runId, ExecutionProgressPayload payload) {
    return newEvent(type, runId, null, payload);
  }

  /** 依頼された操作を進めるため、開始を実行する。 */
  public AgentEvent runStarted(String runId, String reason, String parentRunId) {
    return newEvent(
        AgentEventType.AGENT_RUN_STARTED,
        runId,
        null,
        new AgentRunStartedPayload(runId, reason, parentRunId));
  }

  /** 依頼された操作を進めるため、完了を実行する。 */
  public AgentEvent runCompleted(String runId, long duration) {
    return runCompleted(runId, duration, null);
  }

  /** 依頼された操作を進めるため、完了を実行する。 */
  public AgentEvent runCompleted(String runId, long duration, Long completionTokens) {
    return runCompleted(runId, duration, completionTokens, null);
  }

  /** 依頼された操作を進めるため、完了を実行する。 */
  public AgentEvent runCompleted(
      String runId, long duration, Long completionTokens, Double tokensPerSecond) {
    return runCompleted(runId, duration, completionTokens, null, tokensPerSecond, null);
  }

  /** 依頼された操作を進めるため、完了を実行する。 */
  public AgentEvent runCompleted(
      String runId,
      long duration,
      Long completionTokens,
      Double timeToFirstTokenMillis,
      Double outputTokensPerSecond,
      Double endToEndTokensPerSecond) {
    return newEvent(
        AgentEventType.AGENT_RUN_COMPLETED,
        runId,
        null,
        new AgentRunCompletedPayload(
            runId,
            duration,
            completionTokens,
            timeToFirstTokenMillis,
            outputTokensPerSecond,
            endToEndTokensPerSecond));
  }

  /** 依頼された操作を進めるため、失敗を実行する。 */
  public AgentEvent runFailed(String runId, ErrorInformation error) {
    return newEvent(
        AgentEventType.AGENT_RUN_FAILED, runId, null, new AgentRunFailedPayload(runId, error));
  }

  /** 依頼された操作を進めるため、キャンセル済みを実行する。 */
  public AgentEvent runCancelled(String runId, ErrorInformation error) {
    return newEvent(
        AgentEventType.AGENT_RUN_CANCELLED, runId, null, new AgentRunFailedPayload(runId, error));
  }

  // ---- LLM ----

  /** 実行状況の通知で用いる言語モデル要求開始を返す。 */
  public AgentEvent llmRequestStarted(String runId, String requestId, String feature) {
    return newEvent(
        AgentEventType.LLM_REQUEST_STARTED,
        runId,
        requestId,
        new LlmRequestStartedPayload(requestId, feature));
  }

  /** 実行状況の通知で用いる言語モデル要求失敗を返す。 */
  public AgentEvent llmRequestFailed(
      String runId, String requestId, long durationMs, Throwable error) {
    return newEvent(
        AgentEventType.LLM_REQUEST_FAILED,
        runId,
        requestId,
        new LlmRequestFailedPayload(
            requestId,
            durationMs,
            dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.safeError(error)));
  }

  /** 実行状況の通知で用いる言語モデル応答最初トークンを返す。 */
  public AgentEvent llmResponseFirstToken(String runId, String requestId, long durationMs) {
    return newEvent(
        AgentEventType.LLM_RESPONSE_FIRST_TOKEN,
        runId,
        requestId,
        new LlmResponseFirstTokenPayload(requestId, durationMs));
  }

  /** 実行状況の通知で用いる言語モデル応答完了を返す。 */
  public AgentEvent llmResponseCompleted(String runId, String requestId, long durationMs) {
    return newEvent(
        AgentEventType.LLM_RESPONSE_COMPLETED,
        runId,
        requestId,
        new LlmResponseCompletedPayload(requestId, durationMs));
  }

  // ---- Message ----

  /** 実行状況の通知で用いるメッセージ開始を返す。 */
  public AgentEvent messageStarted(String messageId, String role) {
    return newEvent(
        AgentEventType.MESSAGE_STARTED, null, null, new MessageStartedPayload(messageId, role));
  }

  /** 実行状況の通知で用いるメッセージ差分を返す。 */
  public AgentEvent messageDelta(String messageId, String delta) {
    return newEvent(
        AgentEventType.MESSAGE_DELTA, null, null, new MessageDeltaPayload(messageId, delta));
  }

  /** 実行状況の通知で用いるメッセージ完了を返す。 */
  public AgentEvent messageCompleted(String messageId, String role, String text) {
    return newEvent(
        AgentEventType.MESSAGE_COMPLETED,
        null,
        null,
        new MessageCompletedPayload(messageId, role, text));
  }

  /** 実行状況の通知で用いる記憶統合Suggestedを返す。 */
  public AgentEvent memoryConsolidationSuggested() {
    return newEvent(
        AgentEventType.MEMORY_CONSOLIDATION_SUGGESTED,
        null,
        null,
        new MemoryConsolidationSuggestedPayload());
  }

  // ---- Thinking ----

  /** 実行状況の通知で用いる推論開始を返す。 */
  public AgentEvent thinkingStarted(String thinkingId) {
    return newEvent(
        AgentEventType.THINKING_STARTED, null, null, new ThinkingStartedPayload(thinkingId));
  }

  /** 実行状況の通知で用いる推論差分を返す。 */
  public AgentEvent thinkingDelta(String thinkingId, String delta) {
    return newEvent(
        AgentEventType.THINKING_DELTA, null, null, new ThinkingDeltaPayload(thinkingId, delta));
  }

  /** 実行状況の通知で用いる推論完了を返す。 */
  public AgentEvent thinkingCompleted(String thinkingId, String text) {
    return newEvent(
        AgentEventType.THINKING_COMPLETED,
        null,
        null,
        new ThinkingCompletedPayload(thinkingId, text));
  }

  // ---- Tool ----

  /** 実行状況の通知で用いるツール開始を返す。 */
  public AgentEvent toolStarted(String toolCallId, String toolName, String argumentsSummary) {
    return newEvent(
        AgentEventType.TOOL_STARTED,
        null,
        toolCallId,
        new ToolStartedPayload(toolCallId, toolName, argumentsSummary, null));
  }

  /** 実行状況の通知で用いるツール開始を返す。 */
  public AgentEvent toolStarted(
      String toolCallId, String toolName, String argumentsSummary, String summary) {
    return newEvent(
        AgentEventType.TOOL_STARTED,
        null,
        toolCallId,
        new ToolStartedPayload(toolCallId, toolName, argumentsSummary, summary));
  }

  /** 実行状況の通知で用いるツール完了を返す。 */
  public AgentEvent toolCompleted(
      String toolCallId, String toolName, long duration, String resultSummary) {
    return newEvent(
        AgentEventType.TOOL_COMPLETED,
        null,
        toolCallId,
        new ToolCompletedPayload(
            toolCallId, toolName, duration, resultSummary, null, null, null, null));
  }

  /** 実行状況の通知で用いるツール完了を返す。 */
  public AgentEvent toolCompleted(
      String toolCallId,
      String toolName,
      long duration,
      String resultSummary,
      Integer files,
      Long bytes,
      Integer matches,
      Integer items) {
    return newEvent(
        AgentEventType.TOOL_COMPLETED,
        null,
        toolCallId,
        new ToolCompletedPayload(
            toolCallId, toolName, duration, resultSummary, files, bytes, matches, items));
  }

  /** 実行状況の通知で用いるツール失敗を返す。 */
  public AgentEvent toolFailed(String toolCallId, String toolName, ErrorInformation error) {
    return newEvent(
        AgentEventType.TOOL_FAILED,
        null,
        toolCallId,
        new ToolFailedPayload(toolCallId, toolName, error));
  }

  // ---- Skill selection ----

  /** 実行状況の通知で用いるスキル選択内容開始を返す。 */
  public AgentEvent skillSelectionStarted(String selectionId) {
    return newEvent(
        AgentEventType.SKILL_SELECTION_STARTED,
        null,
        selectionId,
        new SkillSelectionStartedPayload(selectionId));
  }

  /** 実行状況の通知で用いるスキル選択内容完了を返す。 */
  public AgentEvent skillSelectionCompleted(
      String selectionId,
      java.util.List<String> explicitSkillNames,
      java.util.List<String> implicitSkillNames,
      java.util.List<String> warnings) {
    return newEvent(
        AgentEventType.SKILL_SELECTION_COMPLETED,
        null,
        selectionId,
        new SkillSelectionCompletedPayload(
            selectionId, explicitSkillNames, implicitSkillNames, warnings));
  }

  /** 実行状況の通知で用いるスキル選択内容失敗を返す。 */
  public AgentEvent skillSelectionFailed(String selectionId, ErrorInformation error) {
    return newEvent(
        AgentEventType.SKILL_SELECTION_FAILED,
        null,
        selectionId,
        new SkillSelectionFailedPayload(selectionId, error));
  }

  /** 実行状況の通知で用いるスキル振り分け開始を返す。 */
  public AgentEvent skillRoutingStarted(
      String runId, String routingId, int candidateCount, int routingInvocation) {
    return newEvent(
        AgentEventType.SKILL_ROUTING_STARTED,
        runId,
        routingId,
        new SkillRoutingStartedPayload(candidateCount, routingInvocation));
  }

  /** 実行状況の通知で用いるスキル振り分け完了を返す。 */
  public AgentEvent skillRoutingCompleted(
      String runId,
      String routingId,
      long durationMs,
      int candidateCount,
      String selectedSkill,
      int routingInvocation,
      Long selectorDurationMs,
      Long metadataLoadDurationMs,
      Long skillLoadDurationMs,
      java.util.List<String> explicitSkillNames,
      java.util.List<String> implicitSkillNames,
      java.util.List<String> warnings) {
    return newEvent(
        AgentEventType.SKILL_ROUTING_COMPLETED,
        runId,
        routingId,
        new SkillRoutingCompletedPayload(
            durationMs,
            candidateCount,
            selectedSkill,
            routingInvocation,
            selectorDurationMs,
            metadataLoadDurationMs,
            skillLoadDurationMs,
            explicitSkillNames,
            implicitSkillNames,
            warnings));
  }

  /** 実行状況の通知で用いるスキル振り分け失敗を返す。 */
  public AgentEvent skillRoutingFailed(
      String runId,
      String routingId,
      long durationMs,
      int candidateCount,
      int routingInvocation,
      ErrorInformation error) {
    return newEvent(
        AgentEventType.SKILL_ROUTING_FAILED,
        runId,
        routingId,
        new SkillRoutingFailedPayload(durationMs, candidateCount, routingInvocation, error));
  }

  /** 実行状況の通知で用いるスキル候補一覧評価済みを返す。 */
  public AgentEvent skillCandidatesEvaluated(
      String runId,
      String routingId,
      int totalSkillCount,
      long durationMs,
      String actualSelectedSkill,
      boolean selected,
      Boolean top1Hit,
      Boolean top3Hit,
      Boolean top5Hit,
      java.util.List<SkillCandidatesEvaluatedPayload.CandidateScore> topCandidates) {
    return newEvent(
        AgentEventType.SKILL_CANDIDATES_EVALUATED,
        runId,
        routingId,
        new SkillCandidatesEvaluatedPayload(
            totalSkillCount,
            topCandidates.size(),
            durationMs,
            actualSelectedSkill,
            selected,
            top1Hit,
            top3Hit,
            top5Hit,
            topCandidates));
  }

  // ---- Task ----

  /** 実行状況の通知で用いるタスク作成を返す。 */
  public AgentEvent taskCreated(String taskId, String parentTaskId, String title, String status) {
    return newEvent(
        AgentEventType.TASK_CREATED,
        null,
        null,
        new TaskCreatedPayload(taskId, parentTaskId, title, status));
  }

  /** 実行状況の通知で用いるタスク開始を返す。 */
  public AgentEvent taskStarted(String taskId) {
    return newEvent(AgentEventType.TASK_STARTED, null, null, new TaskStartedPayload(taskId));
  }

  /** 実行状況の通知で用いるタスク完了を返す。 */
  public AgentEvent taskCompleted(String taskId, Long duration) {
    return newEvent(
        AgentEventType.TASK_COMPLETED, null, null, new TaskCompletedPayload(taskId, duration));
  }

  /** 実行状況の通知で用いるタスク失敗を返す。 */
  public AgentEvent taskFailed(String taskId, ErrorInformation error) {
    return newEvent(AgentEventType.TASK_FAILED, null, null, new TaskFailedPayload(taskId, error));
  }

  // ---- Working Set ----

  /** 実行状況の通知で用いる作業中集合項目追加済みを返す。 */
  public AgentEvent workingSetItemAdded(
      String itemId, String kind, String identifier, String path, String reason) {
    return workingSetItemAdded(itemId, kind, identifier, path, reason, null);
  }

  /** 実行状況の通知で用いる作業中集合項目追加済みを返す。 */
  public AgentEvent workingSetItemAdded(
      String itemId,
      String kind,
      String identifier,
      String path,
      String reason,
      String correlationId) {
    return newEvent(
        AgentEventType.WORKING_SET_ITEM_ADDED,
        null,
        correlationId,
        new WorkingSetItemAddedPayload(itemId, kind, identifier, path, reason));
  }

  /** 実行状況の通知で用いる作業中集合項目削除済みを返す。 */
  public AgentEvent workingSetItemRemoved(String itemId, String reason) {
    return newEvent(
        AgentEventType.WORKING_SET_ITEM_REMOVED,
        null,
        null,
        new WorkingSetItemRemovedPayload(itemId, reason));
  }

  /** 実行状況の通知で用いる作業中集合検索開始を返す。 */
  public AgentEvent workingSetSearchStarted(
      String searchId, String query, String strategy, int workingSetSizeBefore) {
    return newEvent(
        AgentEventType.WORKING_SET_SEARCH_STARTED,
        null,
        searchId,
        new WorkingSetSearchStartedPayload(searchId, query, strategy, workingSetSizeBefore));
  }

  /** 実行状況の通知で用いる作業中集合検索完了を返す。 */
  public AgentEvent workingSetSearchCompleted(
      String searchId,
      long durationMs,
      int hitCount,
      int candidateCount,
      int selectedCount,
      int alreadyPresentCount,
      int workingSetSizeBefore,
      int workingSetSizeAfter) {
    return newEvent(
        AgentEventType.WORKING_SET_SEARCH_COMPLETED,
        null,
        searchId,
        new WorkingSetSearchCompletedPayload(
            searchId,
            durationMs,
            hitCount,
            candidateCount,
            selectedCount,
            alreadyPresentCount,
            workingSetSizeBefore,
            workingSetSizeAfter));
  }

  /** 実行状況の通知で用いる作業中集合文脈追加されたを返す。 */
  public AgentEvent workingSetContextInjected(int itemCount, int contextCharacters) {
    return newEvent(
        AgentEventType.WORKING_SET_CONTEXT_INJECTED,
        null,
        null,
        new WorkingSetContextInjectedPayload(itemCount, contextCharacters));
  }

  /** 実行状況の通知で用いる文脈追加されたを返す。 */
  public AgentEvent contextInjected(String source, Integer itemCount, int contextCharacters) {
    return newEvent(
        AgentEventType.CONTEXT_INJECTED,
        null,
        null,
        new ContextInjectedPayload(source, itemCount, contextCharacters));
  }

  /** 実行状況の通知で用いる文脈に使える予算評価済みを返す。 */
  public AgentEvent contextBudgetEvaluated(
      int inputBudget,
      int totalTokens,
      java.util.List<String> included,
      java.util.List<String> dropped) {
    return newEvent(
        AgentEventType.CONTEXT_BUDGET_EVALUATED,
        null,
        null,
        new ContextBudgetEvaluatedPayload(inputBudget, totalTokens, included, dropped));
  }

  /** 実行状況の通知で用いる文脈に使える予算余白を除いたを返す。 */
  public AgentEvent contextBudgetTrimmed(
      int inputBudget, int totalTokens, java.util.List<String> dropped) {
    return newEvent(
        AgentEventType.CONTEXT_BUDGET_TRIMMED,
        null,
        null,
        new ContextBudgetTrimmedPayload(inputBudget, totalTokens, dropped));
  }

  /** 実行状況の通知で用いるファイル要約保存済みを返す。 */
  public AgentEvent fileSummarySaved(String path, int summaryCharacters) {
    return newEvent(
        AgentEventType.FILE_SUMMARY_SAVED,
        null,
        null,
        new FileSummarySavedPayload(path, summaryCharacters));
  }

  /** 実行状況の通知で用いるファイル要約Invalidatedを返す。 */
  public AgentEvent fileSummaryInvalidated(String path) {
    return newEvent(
        AgentEventType.FILE_SUMMARY_INVALIDATED,
        null,
        null,
        new FileSummaryInvalidatedPayload(path));
  }

  /** 実行状況の通知で用いるファイル要約古い状態対象外を返す。 */
  public AgentEvent fileSummaryStaleSkipped(String path) {
    return newEvent(
        AgentEventType.FILE_SUMMARY_STALE_SKIPPED,
        null,
        null,
        new FileSummaryStaleSkippedPayload(path));
  }

  /** 実行状況の通知で用いる再開用の記録保存済みを返す。 */
  public AgentEvent checkpointSaved(String taskId, String reason, int workingFileCount) {
    return newEvent(
        AgentEventType.CHECKPOINT_SAVED,
        null,
        null,
        new CheckpointSavedPayload(taskId, reason, workingFileCount));
  }

  /** 実行状況の通知で用いるバックグラウンドプロセス開始を返す。 */
  public AgentEvent backgroundProcessStarted(
      String processId, long pid, String command, String workingDirectory) {
    return newEvent(
        AgentEventType.BACKGROUND_PROCESS_STARTED,
        null,
        processId,
        new BackgroundProcessStartedPayload(
            processId, pid, bounded(command, 200), workingDirectory));
  }

  /** 実行状況の通知で用いるバックグラウンドプロセス完了を返す。 */
  public AgentEvent backgroundProcessCompleted(
      String processId, long pid, Integer exitCode, double elapsedSeconds) {
    return newEvent(
        AgentEventType.BACKGROUND_PROCESS_COMPLETED,
        null,
        processId,
        new BackgroundProcessCompletedPayload(processId, pid, exitCode, elapsedSeconds));
  }

  /** 実行状況の通知で用いるバックグラウンドプロセス失敗を返す。 */
  public AgentEvent backgroundProcessFailed(String processId, String command, Throwable error) {
    return newEvent(
        AgentEventType.BACKGROUND_PROCESS_FAILED,
        null,
        processId,
        new BackgroundProcessFailedPayload(
            processId, bounded(command, 200), ErrorInformation.from(error)));
  }

  /** 実行状況の通知で用いるバックグラウンドプロセス強制終了済みを返す。 */
  public AgentEvent backgroundProcessKilled(
      String processId, long pid, Integer exitCode, double elapsedSeconds) {
    return newEvent(
        AgentEventType.BACKGROUND_PROCESS_KILLED,
        null,
        processId,
        new BackgroundProcessKilledPayload(processId, pid, exitCode, elapsedSeconds));
  }

  // ---- Topic Generator ----

  /** 実行状況の通知で用いる話題生成開始を返す。 */
  public AgentEvent topicGenerationStarted(String runId, String topicGenerationId, String trigger) {
    Instant now = Instant.now(clock);
    return newEvent(
        AgentEventType.TOPIC_GENERATION_STARTED,
        runId,
        topicGenerationId,
        new TopicGenerationStartedPayload(topicGenerationId, bounded(trigger, 80), now));
  }

  /** 実行状況の通知で用いる話題操作がない状態実行条件評価済みを返す。 */
  public AgentEvent topicIdleTriggerEvaluated(
      IdleTriggerRejectReason reason,
      boolean accepted,
      Duration idleDuration,
      Duration requiredIdle) {
    Instant now = Instant.now(clock);
    return newEvent(
        AgentEventType.TOPIC_IDLE_TRIGGER_EVALUATED,
        null,
        null,
        new TopicIdleTriggerEvaluatedPayload(
            accepted, millis(idleDuration), millis(requiredIdle), reason, now));
  }

  /** 実行状況の通知で用いる話題候補一覧Refreshedを返す。 */
  public AgentEvent topicCandidatesRefreshed(int candidateCount) {
    return topicCandidatesRefreshed(candidateCount, List.of());
  }

  /** 実行状況の通知で用いる話題候補一覧Refreshedを返す。 */
  public AgentEvent topicCandidatesRefreshed(int candidateCount, List<String> topics) {
    Instant now = Instant.now(clock);
    return newEvent(
        AgentEventType.TOPIC_CANDIDATES_REFRESHED,
        null,
        null,
        new TopicCandidatesRefreshedPayload(candidateCount, topics, now));
  }

  /** 実行状況の通知で用いる話題候補生成済みを返す。 */
  public AgentEvent topicCandidateGenerated(
      String runId,
      String topicGenerationId,
      String topicCandidateId,
      String topicType,
      String source,
      String topic,
      String reason,
      Double priority,
      Double freshness,
      Double usefulness,
      Double intrusiveness,
      Double confidence) {
    return newEvent(
        AgentEventType.TOPIC_CANDIDATE_GENERATED,
        runId,
        topicGenerationId,
        new TopicCandidateGeneratedPayload(
            topicGenerationId,
            topicCandidateId,
            topicType,
            source,
            bounded(topic, 160),
            bounded(reason, 240),
            priority,
            freshness,
            usefulness,
            intrusiveness,
            confidence));
  }

  /** 実行状況の通知で用いる話題候補評価済みを返す。 */
  public AgentEvent topicCandidateScored(
      String runId, String topicGenerationId, String topicCandidateId, TopicScoreBreakdown score) {
    return newEvent(
        AgentEventType.TOPIC_CANDIDATE_SCORED,
        runId,
        topicGenerationId,
        new TopicCandidateScoredPayload(topicGenerationId, topicCandidateId, score));
  }

  /** 実行状況の通知で用いる話題候補拒否済みを返す。 */
  public AgentEvent topicCandidateRejected(
      String runId,
      String topicGenerationId,
      String topicCandidateId,
      TopicRejectionReason reason,
      Double score) {
    return newEvent(
        AgentEventType.TOPIC_CANDIDATE_REJECTED,
        runId,
        topicGenerationId,
        new TopicCandidateRejectedPayload(topicGenerationId, topicCandidateId, reason, score));
  }

  /** 実行状況の通知で用いる話題選択中を返す。 */
  public AgentEvent topicSelected(
      String runId, String topicGenerationId, String topicCandidateId, Double score, Integer rank) {
    return newEvent(
        AgentEventType.TOPIC_SELECTED,
        runId,
        topicGenerationId,
        new TopicSelectedPayload(topicGenerationId, topicCandidateId, score, rank));
  }

  /** 実行状況の通知で用いる話題読み上げ済みを返す。 */
  public AgentEvent topicSpoken(
      String runId,
      String topicGenerationId,
      String topicCandidateId,
      String messageId,
      Instant spokenAt,
      String message) {
    return newEvent(
        AgentEventType.TOPIC_SPOKEN,
        runId,
        topicGenerationId,
        new TopicSpokenPayload(
            topicGenerationId, topicCandidateId, messageId, spokenAt, bounded(message, 240)));
  }

  /** 実行状況の通知で用いる話題読み上げ対象外を返す。 */
  public AgentEvent topicSpeakSkipped(
      String runId,
      String topicGenerationId,
      String topicCandidateId,
      TopicSpeakSkipReason reason,
      Instant nextSpeakAllowedAt) {
    return newEvent(
        AgentEventType.TOPIC_SPEAK_SKIPPED,
        runId,
        topicGenerationId,
        new TopicSpeakSkippedPayload(
            topicGenerationId, topicCandidateId, reason, nextSpeakAllowedAt));
  }

  /** 実行状況の通知で用いる話題生成完了を返す。 */
  public AgentEvent topicGenerationCompleted(
      String runId,
      String topicGenerationId,
      int candidateCount,
      int scoredCount,
      int rejectedCount,
      String selectedCandidateId,
      boolean spoken,
      long durationMs) {
    Instant now = Instant.now(clock);
    return newEvent(
        AgentEventType.TOPIC_GENERATION_COMPLETED,
        runId,
        topicGenerationId,
        new TopicGenerationCompletedPayload(
            topicGenerationId,
            candidateCount,
            scoredCount,
            rejectedCount,
            selectedCandidateId,
            spoken,
            durationMs,
            now));
  }

  /** 実行状況の通知で用いる話題生成失敗を返す。 */
  public AgentEvent topicGenerationFailed(
      String runId, String topicGenerationId, TopicGenerationStage stage, Throwable error) {
    Instant now = Instant.now(clock);
    String message =
        error == null
            ? "unknown error"
            : error.getClass().getSimpleName() + ": " + error.getMessage();
    return newEvent(
        AgentEventType.TOPIC_GENERATION_FAILED,
        runId,
        topicGenerationId,
        new TopicGenerationFailedPayload(topicGenerationId, stage, bounded(message, 240), now));
  }

  /** 実行状況の通知で用いる話題自動読み上げ抑制されたを返す。 */
  public AgentEvent topicAutoSpeakSuppressed(String reason) {
    Instant now = Instant.now(clock);
    return newEvent(
        AgentEventType.TOPIC_AUTO_SPEAK_SUPPRESSED,
        null,
        null,
        new TopicAutoSpeakSuppressedPayload(bounded(reason, 160), now));
  }

  // ---- Context ----

  /** 実行状況の通知で用いる文脈状態の記録更新を返す。 */
  public AgentEvent contextSnapshotUpdated(Long usedTokens, Long maxTokens, Double utilization) {
    return newEvent(
        AgentEventType.CONTEXT_SNAPSHOT_UPDATED,
        null,
        null,
        new ContextSnapshotUpdatedPayload(usedTokens, maxTokens, utilization));
  }

  // ---- File ----

  /** 実行状況の通知で用いるファイル作成を返す。 */
  public AgentEvent fileCreated(String path) {
    return newEvent(AgentEventType.FILE_CREATED, null, null, new FileCreatedPayload(path));
  }

  /** 実行状況の通知で用いるファイル更新を返す。 */
  public AgentEvent fileModified(String path, Integer addedLines, Integer removedLines) {
    return newEvent(
        AgentEventType.FILE_MODIFIED,
        null,
        null,
        new FileModifiedPayload(path, addedLines, removedLines));
  }

  /** 実行状況の通知で用いるファイル削除済みを返す。 */
  public AgentEvent fileDeleted(String path) {
    return newEvent(AgentEventType.FILE_DELETED, null, null, new FileDeletedPayload(path));
  }

  // ---- 共通 ----
  /** 実行状況の通知で用いるバックグラウンド実行を返す。 */
  public AgentEvent backgroundExecution(
      dev.mikoto2000.rei.core.execution.ActiveExecution execution, String status, String output) {
    var type =
        switch (status) {
          case "RUNNING" -> AgentEventType.EXECUTION_STARTED;
          case "COMPLETED" -> AgentEventType.EXECUTION_COMPLETED;
          case "CANCELLED" -> AgentEventType.EXECUTION_CANCELLED;
          default -> AgentEventType.EXECUTION_FAILED;
        };
    String safe = CredentialRedactor.redact(output).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (safe.length() > 20000) safe = safe.substring(0, 20000) + "... (truncated)";
    return newEvent(
            type,
            execution.id(),
            execution.id(),
            new BackgroundExecutionPayload(
                execution.id(), execution.type(), status, bounded(execution.summary(), 200), safe))
        .withExecutionOwnership(execution);
  }

  /** 実行状況の通知で用いる追加入力を返す。 */
  public AgentEvent intervention(String runId, String inputId, String text, boolean applied) {
    return newEvent(
        applied
            ? AgentEventType.USER_INTERVENTION_APPLIED
            : AgentEventType.USER_INTERVENTION_RECEIVED,
        runId,
        inputId,
        new UserInterventionPayload(inputId, text));
  }

  /** 新たな対象を利用できるよう、イベントを作成する。 */
  private AgentEvent newEvent(
      AgentEventType type, String runId, String correlationId, AgentEventPayload payload) {
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
            payload)
        .withOwnership(dev.mikoto2000.rei.core.chat.AgentRunScope.current())
        .withExecutionOwnership(dev.mikoto2000.rei.core.execution.ExecutionScope.current());
  }

  /** 利用先の上限を超えない量へ、指定された内容を調整する。 */
  private String bounded(String value, int maxLength) {
    if (value == null) return null;
    String safe =
        CredentialRedactor.redact(value)
            .replaceAll("[\\p{Cntrl}]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    return safe.length() <= maxLength ? safe : safe.substring(0, maxLength - 1) + "…";
  }

  /** 実行状況の通知で用いるミリ秒数を返す。 */
  private long millis(Duration duration) {
    return duration == null ? 0L : Math.max(0L, duration.toMillis());
  }
}
