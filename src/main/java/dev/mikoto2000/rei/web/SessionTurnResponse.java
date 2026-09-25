package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.session.SessionTurn;
import java.util.Map;

public record SessionTurnResponse(String turnId,String runId,String userMessage,String assistantMessage,String createdAt,
    String source,String sourceId,Map<String,String> metadata) {
  static SessionTurnResponse from(SessionTurn row) {
    return new SessionTurnResponse(row.runId(),row.runId(),row.userMessage(),row.assistantMessage(),row.createdAt().toString(),
        row.source(),row.sourceId(),row.metadata());
  }
}
