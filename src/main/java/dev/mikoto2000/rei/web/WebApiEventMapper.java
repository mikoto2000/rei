package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.event.*;
import java.util.*;

/** Explicit external event boundary, independent of Shell presentation. */
public final class WebApiEventMapper {
  private WebApiEventMapper() {}
  private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper()
      .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  private static Set<String> fields(AgentEventType type) {
    String names = switch (type) {
      case CONTEXT_COMPRESSION_STARTED, CONTEXT_COMPRESSION_COMPLETED, CONTEXT_COMPRESSION_FAILED ->
          "beforeEstimatedTokens afterEstimatedTokens compressedMessageCount summaryThroughSequence reason";
      case AGENT_RUN_STARTED -> "runId parentRunId";
      case AGENT_RUN_COMPLETED -> "runId duration completionTokens timeToFirstTokenMillis outputTokensPerSecond endToEndTokensPerSecond";
      case AGENT_RUN_FAILED, AGENT_RUN_CANCELLED -> "runId error";
      case MESSAGE_STARTED -> "messageId role";
      case MESSAGE_DELTA -> "messageId delta";
      case MESSAGE_COMPLETED -> "messageId role text";
      case THINKING_STARTED, THINKING_DELTA, THINKING_COMPLETED -> "thinkingId";
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
      case SKILL_ROUTING_COMPLETED -> "durationMs candidateCount selectedSkill routingInvocation selectorDurationMs metadataLoadDurationMs skillLoadDurationMs explicitSkillNames implicitSkillNames";
      case SKILL_ROUTING_FAILED -> "durationMs candidateCount routingInvocation error";
      case SKILL_CANDIDATES_EVALUATED -> "totalSkillCount candidateCount durationMs actualSelectedSkill selected";
      case PROGRESS_DETECTED, STAGNATION_UPDATED, STAGNATION_DETECTED, STAGNATION_REPLAN_REQUESTED,
          STAGNATION_RECOVERED, STAGNATION_STOPPED -> "consecutiveNoProgressIterations threshold stagnationReplanCount maxStagnationReplans";
      case WORKING_SET_ITEM_ADDED -> "itemId kind identifier path";
      case WORKING_SET_ITEM_REMOVED -> "itemId";
      case WORKING_SET_SEARCH_STARTED -> "searchId workingSetSizeBefore";
      case WORKING_SET_SEARCH_COMPLETED -> "searchId durationMs hitCount candidateCount selectedCount alreadyPresentCount workingSetSizeBefore workingSetSizeAfter";
      case WORKING_SET_CONTEXT_INJECTED -> "itemCount contextCharacters";
      default -> "";
    };
    return names.isEmpty() ? Set.of() : Set.of(names.split(" "));
  }
  public static WebApiEventDto from(AgentEvent event, boolean cancelled, String apiKey) {
    Map<String, Object> source = event.payload() == null ? Map.of()
        : MAPPER.convertValue(event.payload(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    Map<String, Object> payload = new LinkedHashMap<>();
    source.forEach((name, value) -> {
      if (fields(event.type()).contains(name) && name.equals("error") && value != null) {
        // Internal exception messages may contain arbitrary credentials, paths or stack traces.
        if (value instanceof Map<?, ?> error && "ContextHardLimit".equals(error.get("errorType")))
          payload.put(name, Map.of("type", "context_hard_limit", "message", "CONTEXT_HARD_LIMIT: context remains too large after compression."));
        else payload.put(name, Map.of("type", "operation_failed", "message", "Operation failed."));
      } else if (fields(event.type()).contains(name)) payload.put(name, clean(value, apiKey));
    });
    String type = cancelled && (event.type() == AgentEventType.AGENT_RUN_FAILED
        || event.type() == AgentEventType.AGENT_RUN_COMPLETED) ? "agent.run.cancelled" : event.type().value();
    return new WebApiEventDto(safeText(event.id(), apiKey), event.sequence(), event.timestamp().toString(), type, 1,
        safeText(event.sessionId(), apiKey), safeText(event.turnId(), apiKey), safeText(event.runId(), apiKey),
        safeText(event.projectId(), apiKey), safeText(event.correlationId(), apiKey), safeText(event.parentEventId(), apiKey),
        Collections.unmodifiableMap(payload));
  }
  private static String safeText(String text, String key) {
    if (text == null) return null;
    String exact = key == null || key.isEmpty() ? text : text.replace(key, "[REDACTED]");
    return CredentialRedactor.redact(exact).replaceAll("(?i)\\b(?:Bearer|Basic)\\s+[^\\s,;\"']+", "[REDACTED]");
  }
  private static Object clean(Object value, String key) {
    if (value instanceof String text) {
      return safeText(text, key);
    }
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      map.forEach((name, child) -> {
        if (!Set.of("stackTrace", "exception", "@class").contains(name.toString())) result.put(name.toString(), clean(child, key));
      });
      return result;
    }
    if (value instanceof List<?> list) return list.stream().map(child -> clean(child, key)).toList();
    return value;
  }
}
