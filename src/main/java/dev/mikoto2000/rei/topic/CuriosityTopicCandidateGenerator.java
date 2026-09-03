package dev.mikoto2000.rei.topic;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class CuriosityTopicCandidateGenerator implements TopicCandidateGenerator {
  private final CuriosityQueue queue;
  private final TopicGeneratorProperties properties;

  public CuriosityTopicCandidateGenerator(CuriosityQueue queue, TopicGeneratorProperties properties) {
    this.queue = queue;
    this.properties = properties;
  }

  @Override
  public List<TopicCandidate> generate(TopicGenerationContext context) {
    if (!properties.isEnabled()) return List.of();
    Instant now = context.currentTime();
    return queue.findCandidates(new CuriosityQuery(now, properties.getMaxCandidates())).stream()
        .map(item -> new TopicCandidate(
            UUID.randomUUID().toString(),
            item.question(),
            item.reason(),
            TopicType.FOLLOW_UP,
            TopicSource.CURIOSITY_QUEUE,
            item.priority(),
            freshness(item, now),
            0.75d,
            0.25d,
            0.80d,
            now))
        .toList();
  }

  private double freshness(CuriosityItem item, Instant now) {
    if (item.expiresAt() == null || !item.expiresAt().isAfter(item.createdAt())) return 0.7d;
    double total = item.expiresAt().getEpochSecond() - item.createdAt().getEpochSecond();
    double remaining = Math.max(0L, item.expiresAt().getEpochSecond() - now.getEpochSecond());
    return Math.max(0.0d, Math.min(1.0d, remaining / total));
  }
}
