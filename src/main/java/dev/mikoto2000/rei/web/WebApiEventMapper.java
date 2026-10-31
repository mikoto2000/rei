package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.CredentialRedactor;
import dev.mikoto2000.rei.event.ExecutionProgressPayload;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Web APIによる操作に必要なWebAPIイベントデータの変換を扱う。
 *
 * <p>Explicit external event boundary, independent of Shell presentation.
 */
public final class WebApiEventMapper {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private WebApiEventMapper() {}

  /** データの変換。 */
  private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** Web APIによる操作で用いる項目一覧を返す。 */
  private static Set<String> fields(AgentEventType type) {
    String names =
        switch (type) {
          case SUBAGENT_STARTED, SUBAGENT_COMPLETED, SUBAGENT_FAILED ->
              "parentRunId subAgentRunId agentId status duration failureReason backendId modelId totalTokens";
          case GOAL_UPDATED -> "goalId status attempts maxRuns llmCallsUsed maxLlmCalls";
          case ATTENTION_REQUIRED -> "attentionId kind message";
          case AUTO_SLEEP_STOPPED,
              MEMORY_SLEEP_STARTED,
              MEMORY_SLEEP_COMPLETED,
              MEMORY_SLEEP_FAILED,
              MEMORY_RETRIEVAL_COMPLETED ->
              "sleepRunId preview processedTurns candidateCount memoryCount status";
          case APPLICATION_SHUTDOWN_STARTED -> "reason";
          case CONTEXT_COMPRESSION_STARTED,
              CONTEXT_COMPRESSION_COMPLETED,
              CONTEXT_COMPRESSION_FAILED,
              CONTEXT_COMPRESSION_SKIPPED ->
              "beforeEstimatedTokens afterEstimatedTokens compressedMessageCount summaryThroughSequence reason details";
          case AGENT_RUN_STARTED -> "runId parentRunId";
          case AGENT_RUN_COMPLETED ->
              "runId duration completionTokens timeToFirstTokenMillis outputTokensPerSecond endToEndTokensPerSecond";
          case AGENT_RUN_FAILED, AGENT_RUN_CANCELLED -> "runId error";
          case MESSAGE_STARTED -> "messageId role";
          case MESSAGE_DELTA -> "messageId delta";
          case MESSAGE_COMPLETED -> "messageId role text";
          case THINKING_STARTED -> "thinkingId";
          case THINKING_DELTA -> "thinkingId delta";
          case THINKING_COMPLETED -> "thinkingId text";
          case TOOL_STARTED -> "toolCallId toolName";
          case TOOL_COMPLETED -> "toolCallId toolName duration files bytes matches items";
          case TOOL_FAILED -> "toolCallId toolName error";
          case LLM_REQUEST_STARTED -> "requestId feature";
          case LLM_RESPONSE_FIRST_TOKEN, LLM_RESPONSE_COMPLETED -> "requestId durationMs";
          case LLM_REQUEST_FAILED -> "requestId durationMs error";
          case SKILL_SELECTION_STARTED -> "selectionId";
          case SKILL_SELECTION_COMPLETED -> "selectionId explicitSkillNames implicitSkillNames";
          case SKILL_SELECTION_FAILED -> "selectionId error";
          case SKILL_ROUTING_STARTED -> "candidateCount routingInvocation";
          case SKILL_ROUTING_COMPLETED ->
              "durationMs candidateCount selectedSkill routingInvocation selectorDurationMs metadataLoadDurationMs skillLoadDurationMs explicitSkillNames implicitSkillNames";
          case SKILL_ROUTING_FAILED -> "durationMs candidateCount routingInvocation error";
          case SKILL_CANDIDATES_EVALUATED ->
              "totalSkillCount candidateCount durationMs actualSelectedSkill selected";
          case PROGRESS_DETECTED,
              STAGNATION_UPDATED,
              STAGNATION_DETECTED,
              STAGNATION_REPLAN_REQUESTED,
              STAGNATION_RECOVERED,
              STAGNATION_STOPPED ->
              "consecutiveNoProgressIterations threshold stagnationReplanCount maxStagnationReplans";
          case WORKING_SET_ITEM_ADDED -> "itemId kind identifier path";
          case WORKING_SET_ITEM_REMOVED -> "itemId";
          case WORKING_SET_SEARCH_STARTED -> "searchId workingSetSizeBefore";
          case WORKING_SET_SEARCH_COMPLETED ->
              "searchId durationMs hitCount candidateCount selectedCount alreadyPresentCount workingSetSizeBefore workingSetSizeAfter";
          case WORKING_SET_CONTEXT_INJECTED -> "itemCount contextCharacters";
          case DEPENDENCY_FAILED, DEPENDENCY_CANCELLED -> "dependencyId state";
          case TASK_FAILED -> "taskId error";
          case WORK_CONTEXT_UPDATED, WORK_CONTEXT_UPDATE_FAILED -> "status revision";
          case COMPUTER_USE_PROGRESS -> "step phase action target x y confidence reason";
          case EXECUTION_STARTED, EXECUTION_COMPLETED, EXECUTION_FAILED, EXECUTION_CANCELLED ->
              "executionId executionType status request output";
          case HISTORY_SEARCH_COMPLETED ->
              "scope currentProjectHitCount crossProjectHitCount searchedProjectCount";
          case CONTEXT_INJECTED -> "source itemCount contextCharacters";
          case CONTEXT_BUDGET_EVALUATED -> "totalTokens inputBudget included dropped";
          case CONTEXT_BUDGET_TRIMMED -> "dropped";
          case FILE_SUMMARY_SAVED -> "path summaryCharacters";
          case FILE_SUMMARY_INVALIDATED, FILE_SUMMARY_STALE_SKIPPED, FILE_CREATED, FILE_DELETED ->
              "path";
          case FILE_MODIFIED -> "path addedLines removedLines";
          case BACKGROUND_PROCESS_STARTED -> "processId pid";
          case BACKGROUND_PROCESS_COMPLETED, BACKGROUND_PROCESS_KILLED ->
              "processId exitCode elapsedSeconds";
          case BACKGROUND_PROCESS_FAILED -> "processId error";
          case DELEGATION_STARTED, DELEGATION_COMPLETED, DELEGATION_FAILED, DELEGATION_CANCELLED ->
              "agent summary";
          case TOPIC_CANDIDATES_REFRESHED -> "candidateCount topics";
          case TOPIC_SPOKEN -> "candidateId messageId";
          case TOPIC_SPEAK_SKIPPED, TOPIC_AUTO_SPEAK_SUPPRESSED -> "candidateId reason";
          case TOPIC_GENERATION_FAILED -> "stage errorSummary";
          default -> "";
        };
    return names.isEmpty() ? Set.of() : Set.of(names.split(" "));
  }

  /** Web APIによる操作で利用する情報を、受け取った形式から変換する。 */
  public static WebApiEventDto from(AgentEvent event, boolean cancelled, String apiKey) {
    Map<String, Object> source =
        event.payload() == null
            ? Map.of()
            : MAPPER.convertValue(
                event.payload(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    Map<String, Object> payload = new LinkedHashMap<>();
    // Public state marker only; arbitrary internal reasons remain private.
    if (event.type() == AgentEventType.STAGNATION_UPDATED
        && event.payload() instanceof ExecutionProgressPayload progress
        && "waiting_for_dependency".equals(progress.reason()))
      payload.put("reason", "waiting_for_dependency");
    source.forEach(
        (name, value) -> {
          if (fields(event.type()).contains(name) && name.equals("error") && value != null) {
            // Internal exception messages may contain arbitrary credentials, paths or stack traces.
            if (value instanceof Map<?, ?> error
                && dev.mikoto2000.rei.toolcontract.FileResultBudgetExceededException.CODE.equals(
                    error.get("code")))
              payload.put(
                  name,
                  Map.of(
                      "type",
                          dev.mikoto2000.rei.toolcontract.FileResultBudgetExceededException.CODE,
                      "message",
                          dev.mikoto2000.rei.toolcontract.FileResultBudgetExceededException
                              .MESSAGE));
            else if (value instanceof Map<?, ?> error
                && "ContextHardLimit".equals(error.get("errorType")))
              payload.put(
                  name,
                  Map.of(
                      "type",
                      "context_hard_limit",
                      "message",
                      "CONTEXT_HARD_LIMIT: context remains too large after compression."));
            else if ((event.type() == AgentEventType.LLM_REQUEST_FAILED
                    || event.type() == AgentEventType.AGENT_RUN_FAILED)
                && value instanceof Map<?, ?> error
                && "timeout".equals(error.get("code")))
              // Only the known code selects this fixed public message; never forward error text.
              payload.put(
                  name,
                  Map.of(
                      "type", "timeout",
                      "message", "応答待ちがタイムアウトしました。接続先とタイムアウト設定を確認し、実行結果を確認してから再開してください。"));
            else if (event.type() == AgentEventType.AGENT_RUN_FAILED
                && value instanceof Map<?, ?> error)
              payload.put(name, runStopError(error.get("errorType")));
            else
              payload.put(name, Map.of("type", "operation_failed", "message", "Operation failed."));
          } else if (fields(event.type()).contains(name)) payload.put(name, clean(value, apiKey));
        });
    String type =
        cancelled
                && (event.type() == AgentEventType.AGENT_RUN_FAILED
                    || event.type() == AgentEventType.AGENT_RUN_COMPLETED)
            ? "agent.run.cancelled"
            : event.type().value();
    return new WebApiEventDto(
        safeText(event.id(), apiKey),
        event.sequence(),
        event.timestamp().toString(),
        type,
        1,
        safeText(event.sessionId(), apiKey),
        safeText(event.turnId(), apiKey),
        safeText(event.runId(), apiKey),
        safeText(event.projectId(), apiKey),
        safeText(event.correlationId(), apiKey),
        safeText(event.parentEventId(), apiKey),
        Collections.unmodifiableMap(payload));
  }

  /** 既知の停止理由は固定文で公開し、例外に含まれる任意のメッセージは公開しない。 */
  private static Map<String, String> runStopError(Object errorType) {
    return switch (java.util.Objects.toString(errorType, "")) {
      case "ReplanBudgetExceeded" ->
          Map.of(
              "type",
              "replan_budget_exceeded",
              "message",
              "実行全体の再計画回数の上限に達しました。進捗による停滞カウンターのリセット後も、消費済みの再計画回数は保持されます。依頼の範囲や手順を見直して新しい実行を開始してください。");
      case "Stagnated" ->
          Map.of(
              "type", "stagnated",
              "message", "再計画後も進捗が得られなかったため停止しました。依頼の範囲や手順を見直してください。");
      case "LlmCallBudgetExceeded" ->
          Map.of(
              "type", "llm_call_budget_exceeded",
              "message", "実行全体のモデル呼び出し回数の上限に達しました。依頼を小さく分けて新しい実行を開始してください。");
      default -> Map.of("type", "operation_failed", "message", "Operation failed.");
    };
  }

  /** Web APIによる操作で用いる安全性テキストを返す。 */
  private static String safeText(String text, String key) {
    if (text == null) return null;
    String exact = key == null || key.isEmpty() ? text : text.replace(key, "[REDACTED]");
    return CredentialRedactor.redact(exact)
        .replaceAll("(?i)\\b(?:Bearer|Basic)\\s+[^\\s,;\"']+", "[REDACTED]");
  }

  /** Web APIによる操作で用いる変更なしを返す。 */
  private static Object clean(Object value, String key) {
    if (value instanceof String text) {
      return safeText(text, key);
    }
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      map.forEach(
          (name, child) -> {
            if (!Set.of("stackTrace", "exception", "@class").contains(name.toString()))
              result.put(name.toString(), clean(child, key));
          });
      return result;
    }
    if (value instanceof List<?> list)
      return list.stream().map(child -> clean(child, key)).toList();
    return value;
  }
}
