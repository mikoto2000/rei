package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class DiscoveryTopicCandidateGeneratorTest {

  @Test
  void generatesLimitedSeedsFromWorkingSetRecentTopicAndConversation() {
    TopicGeneratorProperties properties = enabledProperties();
    properties.getDiscovery().setMaxSeeds(2);
    DiscoverySeedGenerator seedGenerator = new DiscoverySeedGenerator(properties);

    List<String> seeds = seedGenerator.generate(new TopicGenerationContext(
        List.of(new ConversationTopicMessage("user", "conversation seed", now())),
        List.of(new WorkingSetTopicItem("F:/project/Dify MCP authentication.md", "file", now())),
        now(),
        List.of("recent topic")));

    assertEquals(2, seeds.size());
    assertEquals("Dify MCP authentication.md", seeds.getFirst());
  }

  @Test
  void sourceItemsBecomeDiscoveryTopicCandidates() {
    TopicGeneratorProperties properties = enabledProperties();
    DiscoveryTopicCandidateGenerator generator = generator(properties, List.of(context -> List.of(
        new DiscoveryItem("1", "Dify MCP authentication release", "related release", "https://example.test/1",
            TopicSource.GITHUB, now().minusSeconds(3600), 0.9, "Dify MCP authentication"))));

    List<TopicCandidate> candidates = generator.generate(seedContext());

    assertEquals(1, candidates.size());
    assertEquals(TopicType.DISCOVERY, candidates.getFirst().type());
    assertEquals(TopicSource.GITHUB, candidates.getFirst().source());
  }

  @Test
  void sourceFailureDoesNotBreakOtherSources() {
    TopicGeneratorProperties properties = enabledProperties();
    DiscoverySource failing = context -> { throw new RuntimeException("boom"); };
    DiscoverySource succeeding = context -> List.of(new DiscoveryItem("ok", "ok", "summary", "url",
        TopicSource.WEB, now(), 0.9, "seed"));

    assertEquals(1, generator(properties, List.of(failing, succeeding)).generate(seedContext()).size());
  }

  @Test
  void lowRelevanceAndSeenItemsAreExcluded() {
    TopicGeneratorProperties properties = enabledProperties();
    InMemoryDiscoverySeenRepository seen = new InMemoryDiscoverySeenRepository();
    seen.markSeen(TopicSource.WEB, "seen");
    DiscoveryTopicCandidateGenerator generator = new DiscoveryTopicCandidateGenerator(new DiscoverySeedGenerator(properties),
        List.of(context -> List.of(
            new DiscoveryItem("low", "low", "", "low", TopicSource.WEB, now(), 0.2, "seed"),
            new DiscoveryItem("seen", "seen", "", "seen", TopicSource.WEB, now(), 0.9, "seed"))),
        seen,
        properties);

    assertTrue(generator.generate(seedContext()).isEmpty());
  }

  @Test
  void oldItemHasLowerFreshness() {
    TopicGeneratorProperties properties = enabledProperties();
    DiscoveryTopicCandidateGenerator generator = generator(properties, List.of());

    assertTrue(generator.freshness(now().minusSeconds(40L * 24 * 60 * 60), now()) < generator.freshness(now(), now()));
  }

  @Test
  void seenItemIsNotReturnedTwice() {
    TopicGeneratorProperties properties = enabledProperties();
    DiscoverySource source = context -> List.of(new DiscoveryItem("same", "same", "", "url", TopicSource.WEB,
        now(), 0.9, "seed"));
    DiscoveryTopicCandidateGenerator generator = generator(properties, List.of(source));

    assertEquals(1, generator.generate(seedContext()).size());
    assertTrue(generator.generate(seedContext()).isEmpty());
  }

  @Test
  void discoveryCandidatePassesThroughRankerAndCooldownPolicy() {
    TopicCandidate candidate = new TopicCandidate("id", "discovery", "", TopicType.DISCOVERY, TopicSource.WEB,
        1, 1, 1, 0, 1, now());
    DeterministicTopicRanker ranker = new DeterministicTopicRanker();
    DefaultSpeakDecisionPolicy policy = new DefaultSpeakDecisionPolicy(enabledProperties());

    assertEquals(SpeakDecisionStatus.DO_NOT_SPEAK,
        policy.decide(ranker.rank(List.of(candidate), new TopicRankingContext(List.of())),
            new SpeakDecisionContext(now(), now().minusSeconds(60), List.of(), false, false)).decision());
  }

  private DiscoveryTopicCandidateGenerator generator(TopicGeneratorProperties properties, List<DiscoverySource> sources) {
    return new DiscoveryTopicCandidateGenerator(new DiscoverySeedGenerator(properties), sources,
        new InMemoryDiscoverySeenRepository(), properties);
  }

  private TopicGenerationContext seedContext() {
    return new TopicGenerationContext(List.of(), List.of(new WorkingSetTopicItem("Dify MCP authentication", "file", now())),
        now(), List.of());
  }

  private TopicGeneratorProperties enabledProperties() {
    TopicGeneratorProperties properties = new TopicGeneratorProperties();
    properties.setEnabled(true);
    return properties;
  }

  private Instant now() {
    return Instant.parse("2026-09-02T00:00:00Z");
  }
}
