package dev.mikoto2000.rei.topic;

import java.time.Instant;

public record TopicCandidate(
    String id,
    String topic,
    String reason,
    TopicType type,
    TopicSource source,
    double priority,
    double freshness,
    double usefulness,
    double intrusiveness,
    double confidence,
    Instant createdAt) {

  public TopicCandidate {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
    if (topic == null || topic.isBlank()) throw new IllegalArgumentException("topic must not be blank");
    if (reason == null) reason = "";
    if (type == null) throw new IllegalArgumentException("type must not be null");
    if (source == null) throw new IllegalArgumentException("source must not be null");
    priority = clamp(priority);
    freshness = clamp(freshness);
    usefulness = clamp(usefulness);
    intrusiveness = clamp(intrusiveness);
    confidence = clamp(confidence);
    if (createdAt == null) throw new IllegalArgumentException("createdAt must not be null");
  }

  private static double clamp(double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0d;
    return Math.max(0.0d, Math.min(1.0d, value));
  }
}
