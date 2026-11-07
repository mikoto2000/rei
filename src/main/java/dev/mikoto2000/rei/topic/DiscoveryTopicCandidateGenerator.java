package dev.mikoto2000.rei.topic;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 利用先の検出話題候補の内容を生成する。 */
public class DiscoveryTopicCandidateGenerator implements TopicCandidateGenerator {
  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(DiscoveryTopicCandidateGenerator.class);

  /** 生成の初期値生成。 */
  private final DiscoverySeedGenerator seedGenerator;

  /** 取得元一覧。 */
  private final List<DiscoverySource> sources;

  /** 確認済み保存と検索の窓口。 */
  private final DiscoverySeenRepository seenRepository;

  /** この機能の動作設定。 */
  private final Supplier<TopicSettings> settings;

  /** 指定された値や利用先を使って、DiscoveryTopicCandidateGeneratorを初期化する。 */
  public DiscoveryTopicCandidateGenerator(
      DiscoverySeedGenerator seedGenerator,
      List<DiscoverySource> sources,
      DiscoverySeenRepository seenRepository,
      TopicGeneratorProperties properties) {
    this.seedGenerator = seedGenerator;
    this.sources = sources == null ? List.of() : List.copyOf(sources);
    this.seenRepository = seenRepository;
    this.settings = () -> TopicSettings.capture(properties);
  }

  /** 指定された条件に従って、依頼された内容を生成する。 */
  @Override
  public List<TopicCandidate> generate(TopicGenerationContext context) {
    var topicSettings = settings.get();
    if (!topicSettings.enabled() || !topicSettings.discoveryEnabled()) return List.of();
    List<String> seeds = seedGenerator.generate(context);
    if (seeds.isEmpty()) return List.of();
    List<TopicCandidate> candidates = new ArrayList<>();
    DiscoveryContext discoveryContext = new DiscoveryContext(seeds, context.currentTime());
    for (DiscoverySource source : sources) {
      try {
        for (DiscoveryItem item : source.discover(discoveryContext)) {
          if (candidates.size() >= topicSettings.maxCandidates()) break;
          if (item.relevance() < topicSettings.minimumRelevance()) continue;
          if (seenRepository.isSeen(item.source(), item.externalId())) continue;
          candidates.add(toCandidate(item, context.currentTime()));
          seenRepository.markSeen(item.source(), item.externalId());
        }
      } catch (java.io.IOException | InterruptedException | RuntimeException e) {
        log.warn("Discovery source failed: {}", source.getClass().getSimpleName());
      }
    }
    return candidates;
  }

  /** 利用先へ渡せるよう、候補の形式に変換する。 */
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

  /** 話題の管理で用いる情報の鮮度を返す。 */
  double freshness(Instant publishedAt, Instant now) {
    var topicSettings = settings.get();
    if (publishedAt == null) return 0.5d;
    long days = Math.max(0L, Duration.between(publishedAt, now).toDays());
    if (days >= topicSettings.freshnessWindowDays()) return 0.1d;
    double remaining = topicSettings.freshnessWindowDays() - days;
    return Math.max(0.1d, Math.min(1.0d, remaining / topicSettings.freshnessWindowDays()));
  }
}
