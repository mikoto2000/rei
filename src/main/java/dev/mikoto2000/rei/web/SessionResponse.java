package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.session.SessionMetadata;

public record SessionResponse(String sessionId, String projectId, String title, String createdAt, String updatedAt) {
  static SessionResponse from(SessionMetadata row) {
    return new SessionResponse(row.sessionId(), row.projectId(), row.title(), row.createdAt().toString(), row.updatedAt().toString());
  }
}
