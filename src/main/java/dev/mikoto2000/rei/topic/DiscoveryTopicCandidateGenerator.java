package dev.mikoto2000.rei.topic;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DiscoveryTopicCandidateGenerator implements TopicCandidateGenerator {
  private static final Logger log = LoggerFactory.getLogger(DiscoveryTopicCandidateGenerator.class);

  private final DiscoverySeedGenerator seedGenerator;
  private final List<DiscoverySource> sources;
  private final DiscoverySeenRepository seenRepository;
  private final TopicGeneratorProperties properties;

  public DiscoveryTopicCandidateGenerator(DiscoverySeedGenerator seedGenerator, List<DiscoverySource> sources,
      DiscoverySeenRepository seenRepository, TopicGeneratorProperties properties) {
    this.seedGenerator = seedGenerator;
    this.sources = sources == null ? List.of() : List.copyOf(sources);
    this.seenRepository = seenRepository;
    this.properties = properties;
  }

  @Override
  public List<TopicCandidate> generate(TopicGenerationContext context) {
    if (!properties.isEnabled() || !properties.getDiscovery().isEnabled()) return List.of();
    List<String> seeds = seedGenerator.generate(context);
    if (seeds.isEmpty()) return List.of();
    List<TopicCandidate> candidates = new ArrayList<>();
    DiscoveryContext discoveryContext = new DiscoveryContext(seeds, context.currentTime());
    for (DiscoverySource source : sources) {
      try {
        for (DiscoveryItem item : source.discover(discoveryContext)) {
          if (candidates.size() >= properties.getMaxCandidates()) break;
          if (item.relevance() < properties.getDiscovery().getMinimumRelevance()) continue;
          if (seenRepository.isSeen(item.source(), item.externalId())) continue;
          candidates.add(toCandidate(item, context.currentTime()));
          seenRepository.markSeen(item.source(), item.externalId());
        }
      } catch (Exception e) {
        log.warn("Discovery source failed: {}", source.getClass().getSimpleName());
      }
    }
    return candidates;
  }

  private TopicCandidate toCandidate(DiscoveryItem item, Instant now) {
    return new TopicCandidate(
        UUID.randomUUID().toString(),
        item.title().isBlank() ? "関連情報が見つかりました" : item.title(),
        item.summary().isBlank() ? "seed: " + item.seed() : item.summary(),
        TopicType.DISCOVERY,
        item.source(),
        0.70d,
        freshness(item.publishedAt(), now),
        Math.max(0.60d, item.relevance()),
        0.30d,
        Math.max(0.60d, item.relevance()),
        now);
  }

  double freshness(Instant publishedAt, Instant now) {
    if (publishedAt == null) return 0.5d;
    long days = Math.max(0L, Duration.between(publishedAt, now).toDays());
    if (days >= properties.getDiscovery().getFreshnessWindowDays()) return 0.1d;
    double remaining = properties.getDiscovery().getFreshnessWindowDays() - days;
    return Math.max(0.1d, Math.min(1.0d, remaining / properties.getDiscovery().getFreshnessWindowDays()));
  }
}
