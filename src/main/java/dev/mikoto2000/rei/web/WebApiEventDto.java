package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.event.*;
import java.util.*;

/** Version 1 explicitly projects payload fields; future internal fields are not automatically public. */
public record WebApiEventDto(String id, long sequence, String timestamp, String type, int version,
    String sessionId, String turnId, String runId, String projectId, String correlationId,
    String parentEventId, Map<String, Object> payload) {
  private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper()
      .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  private static final Set<String> V1_FIELDS = Set.of(
      "runId", "reason", "parentRunId", "duration", "completionTokens", "timeToFirstTokenMillis",
      "outputTokensPerSecond", "endToEndTokensPerSecond", "error", "messageId", "role", "text", "delta",
      "thinkingId", "toolCallId", "toolName", "argumentsSummary", "summary", "resultSummary", "files", "bytes",
      "matches", "items", "selectionId", "explicitSkillNames", "implicitSkillNames", "warnings", "routingId",
      "durationMs", "candidateCount", "selectedSkill", "routingInvocation", "selectorDurationMs", "metadataLoadDurationMs",
      "skillLoadDurationMs", "itemId", "kind", "identifier", "path", "searchId", "usedTokens", "maxTokens",
      "utilization", "inputId", "applied", "addedLines", "removedLines", "requestId", "feature", "status",
      "taskId", "parentTaskId", "title", "processId", "command", "pid", "exitCode", "elapsedSeconds",
      "executionId", "output", "type", "message");

  public static WebApiEventDto from(AgentEvent event, boolean cancelled, String apiKey) {
    Map<String, Object> source = event.payload() == null ? Map.of()
        : MAPPER.convertValue(event.payload(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    Map<String, Object> payload = new LinkedHashMap<>();
    source.forEach((name, value) -> {
      if (name.equals("error") && value instanceof Map<?, ?> error) {
        var external = new LinkedHashMap<String, Object>();
        external.put("type", clean(error.get("errorType"), apiKey));
        external.put("message", clean(error.get("message"), apiKey));
        payload.put(name, external);
      } else if (V1_FIELDS.contains(name)) payload.put(name, clean(value, apiKey));
    });
    String type = cancelled && (event.type() == AgentEventType.AGENT_RUN_FAILED
        || event.type() == AgentEventType.AGENT_RUN_COMPLETED) ? "agent.run.cancelled" : event.type().value();
    return new WebApiEventDto(event.id(), event.sequence(), event.timestamp().toString(), type, 1,
        event.sessionId(), event.turnId(), event.runId(), event.projectId(), event.correlationId(), event.parentEventId(),
        Collections.unmodifiableMap(payload));
  }
  private static Object clean(Object value, String key) {
    if (value instanceof String text) {
      String safe = CredentialRedactor.redact(text);
      return key == null || key.isEmpty() ? safe : safe.replace(key, "[REDACTED]");
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
