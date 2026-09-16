package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.session.SessionTurn;

public record SessionTurnResponse(String turnId, String runId, String userMessage, String assistantMessage, String createdAt) {
  static SessionTurnResponse from(SessionTurn row) {
    return new SessionTurnResponse(row.runId(), row.runId(), row.userMessage(), row.assistantMessage(), row.createdAt().toString());
  }
}
