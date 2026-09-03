package dev.mikoto2000.rei.topic;

import java.time.Instant;

public record CuriosityItem(
    String id,
    String question,
    String reason,
    TopicSource source,
    double priority,
    Instant createdAt,
    Instant expiresAt,
    CuriosityStatus status) {

  public CuriosityItem {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
    if (question == null || question.isBlank()) throw new IllegalArgumentException("question must not be blank");
    if (reason == null) reason = "";
    if (source == null) source = TopicSource.CONVERSATION;
    priority = Math.max(0.0d, Math.min(1.0d, priority));
    if (createdAt == null) throw new IllegalArgumentException("createdAt must not be null");
    if (status == null) status = CuriosityStatus.PENDING;
  }

  public CuriosityItem withStatus(CuriosityStatus newStatus) {
    return new CuriosityItem(id, question, reason, source, priority, createdAt, expiresAt, newStatus);
  }
}
