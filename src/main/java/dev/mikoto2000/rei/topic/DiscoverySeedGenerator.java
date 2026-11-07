package dev.mikoto2000.rei.topic;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

/** 利用先の検出生成の初期値の内容を生成する。 */
public class DiscoverySeedGenerator {
  /** この機能の動作設定。 */
  private final Supplier<TopicSettings> settings;

  /** 指定された値や利用先を使って、DiscoverySeedGeneratorを初期化する。 */
  public DiscoverySeedGenerator(TopicGeneratorProperties properties) {
    this.settings = () -> TopicSettings.capture(properties);
  }

  /** 指定された条件に従って、依頼された内容を生成する。 */
  public List<String> generate(TopicGenerationContext context) {
    var topicSettings = settings.get();
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
    return seeds.stream().limit(topicSettings.maxSeeds()).toList();
  }

  /** 話題の管理で用いる生成の初期値テキストを返す。 */
  private String seedText(String value) {
    if (value == null) return "";
    String safe = value.replace('\\', '/');
    int slash = safe.lastIndexOf('/');
    if (slash >= 0) safe = safe.substring(slash + 1);
    safe = safe.replaceAll("\\s+", " ").trim();
    return safe.length() <= 80 ? safe : safe.substring(0, 80);
  }
}
