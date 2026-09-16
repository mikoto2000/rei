package dev.mikoto2000.rei.application.session;

import java.time.Instant;
import java.util.Objects;

public record SessionMetadata(String sessionId, String projectId, String title, Instant createdAt, Instant updatedAt) {
  public SessionMetadata {
    Objects.requireNonNull(sessionId); Objects.requireNonNull(projectId); Objects.requireNonNull(title);
    Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
  }
  public SessionMetadata touched(Instant time) {
    return new SessionMetadata(sessionId, projectId, title, createdAt, time.isAfter(updatedAt) ? time : updatedAt);
  }
}
