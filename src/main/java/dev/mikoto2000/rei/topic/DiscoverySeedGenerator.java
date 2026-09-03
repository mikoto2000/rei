package dev.mikoto2000.rei.topic;

import java.util.LinkedHashSet;
import java.util.List;

public class DiscoverySeedGenerator {
  private final TopicGeneratorProperties properties;

  public DiscoverySeedGenerator(TopicGeneratorProperties properties) {
    this.properties = properties;
  }

  public List<String> generate(TopicGenerationContext context) {
    LinkedHashSet<String> seeds = new LinkedHashSet<>();
    context.workingSet().stream()
        .map(WorkingSetTopicItem::identifier)
        .map(this::seedText)
        .filter(seed -> !seed.isBlank())
        .forEach(seeds::add);
    context.recentTopics().stream()
        .map(this::seedText)
        .filter(seed -> !seed.isBlank())
        .forEach(seeds::add);
    context.recentConversation().stream()
        .map(ConversationTopicMessage::content)
        .map(this::seedText)
        .filter(seed -> !seed.isBlank())
        .forEach(seeds::add);
    return seeds.stream().limit(properties.getDiscovery().getMaxSeeds()).toList();
  }

  private String seedText(String value) {
    if (value == null) return "";
    String safe = value.replace('\\', '/');
    int slash = safe.lastIndexOf('/');
    if (slash >= 0) safe = safe.substring(slash + 1);
    safe = safe.replaceAll("\\s+", " ").trim();
    return safe.length() <= 80 ? safe : safe.substring(0, 80);
  }
}
