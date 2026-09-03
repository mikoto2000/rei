package dev.mikoto2000.rei.topic;

public interface DiscoverySeenRepository {
  boolean isSeen(TopicSource source, String externalId);
  void markSeen(TopicSource source, String externalId);
}
