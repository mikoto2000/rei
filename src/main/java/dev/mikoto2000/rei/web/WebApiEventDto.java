package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.event.*;
import java.util.*;

/** Version 1 explicitly projects payload fields; future internal fields are not automatically public. */
public record WebApiEventDto(String id, long sequence, String timestamp, String type, int version,
    String sessionId, String turnId, String runId, String projectId, String correlationId,
    String parentEventId, Map<String, Object> payload) {
  public static WebApiEventDto from(AgentEvent event, boolean cancelled, String apiKey) {
    return WebApiEventMapper.from(event, cancelled, apiKey);
  }
}

