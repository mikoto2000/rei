package dev.mikoto2000.rei.topic;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class InMemoryDiscoverySeenRepository implements DiscoverySeenRepository {
  private final Set<String> seen = new LinkedHashSet<>();

  @Override
  public synchronized boolean isSeen(TopicSource source, String externalId) {
    return seen.contains(key(source, externalId));
  }

  @Override
  public synchronized void markSeen(TopicSource source, String externalId) {
    seen.add(key(source, externalId));
  }

  private String key(TopicSource source, String externalId) {
    return source.name() + ":" + (externalId == null ? "" : externalId);
  }
}
