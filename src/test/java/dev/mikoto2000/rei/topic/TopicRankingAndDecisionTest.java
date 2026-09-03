package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class TopicRankingAndDecisionTest {
  private final DeterministicTopicRanker ranker = new DeterministicTopicRanker();

  @Test
  void highScoreCandidateIsSelectedFromMultipleCandidates() {
    var ranked = ranker.rank(List.of(low("low"), high("high")), new TopicRankingContext(List.of()));
    TopicGeneratorProperties properties = new TopicGeneratorProperties();
    DefaultSpeakDecisionPolicy policy = new DefaultSpeakDecisionPolicy(properties);

    SpeakDecision decision = policy.decide(ranked, context(null));

    assertEquals(SpeakDecisionStatus.SPEAK, decision.decision());
    assertEquals("high", decision.selected().candidate().topic());
  }

  @Test
  void lowScoreCandidateDoesNotSpeak() {
    TopicGeneratorProperties properties = new TopicGeneratorProperties();
    DefaultSpeakDecisionPolicy policy = new DefaultSpeakDecisionPolicy(properties);

    SpeakDecision decision = policy.decide(ranker.rank(List.of(low("low")), new TopicRankingContext(List.of())),
        context(null));

    assertEquals(SpeakDecisionStatus.DO_NOT_SPEAK, decision.decision());
  }

  @Test
  void lowConfidenceCandidateDoesNotSpeak() {
    TopicCandidate candidate = new TopicCandidate("id", "candidate", "", TopicType.FOLLOW_UP, TopicSource.CONVERSATION,
        1, 1, 1, 0, 0.2, Instant.parse("2026-09-02T00:00:00Z"));
    DefaultSpeakDecisionPolicy policy = new DefaultSpeakDecisionPolicy(new TopicGeneratorProperties());

    SpeakDecision decision = policy.decide(ranker.rank(List.of(candidate), new TopicRankingContext(List.of())),
        context(null));

    assertEquals(SpeakDecisionStatus.DO_NOT_SPEAK, decision.decision());
  }

  @Test
  void intrusivenessLowersScore() {
    double calm = ranker.rank(List.of(high("calm")), new TopicRankingContext(List.of())).getFirst().finalScore();
    TopicCandidate intrusive = new TopicCandidate("id", "intrusive", "", TopicType.FOLLOW_UP, TopicSource.CONVERSATION,
        1, 1, 1, 1, 1, Instant.parse("2026-09-02T00:00:00Z"));
    double noisy = ranker.rank(List.of(intrusive), new TopicRankingContext(List.of())).getFirst().finalScore();

    assertTrue(noisy < calm);
  }

  @Test
  void repetitionPenaltyLowersScore() {
    double original = ranker.rank(List.of(high("same topic")), new TopicRankingContext(List.of())).getFirst().finalScore();
    double repeated = ranker.rank(List.of(high("same topic")), new TopicRankingContext(List.of("same   topic")))
        .getFirst().finalScore();

    assertTrue(repeated < original);
  }

  @Test
  void scoreBreakdownExplainsFinalScore() {
    TopicCandidate candidate = new TopicCandidate("id", "candidate", "", TopicType.FOLLOW_UP, TopicSource.CONVERSATION,
        0.8, 0.7, 0.6, 0.2, 0.9, Instant.parse("2026-09-02T00:00:00Z"));

    TopicScoreBreakdown score = ranker.rank(List.of(candidate), new TopicRankingContext(List.of("candidate")))
        .getFirst().score();

    assertEquals(0.16, score.priorityContribution(), 0.0001);
    assertEquals(0.14, score.freshnessContribution(), 0.0001);
    assertEquals(0.18, score.usefulnessContribution(), 0.0001);
    assertEquals(0.18, score.confidenceContribution(), 0.0001);
    assertEquals(0.05, score.intrusivenessPenalty(), 0.0001);
    assertEquals(0.40, score.repetitionPenalty(), 0.0001);
    assertEquals(0.21, score.finalScore(), 0.0001);
  }

  @Test
  void cooldownPreventsSpeakingAndAllowsAfterInterval() {
    TopicGeneratorProperties properties = new TopicGeneratorProperties();
    DefaultSpeakDecisionPolicy policy = new DefaultSpeakDecisionPolicy(properties);
    var ranked = ranker.rank(List.of(high("topic")), new TopicRankingContext(List.of()));

    assertEquals(SpeakDecisionStatus.DO_NOT_SPEAK,
        policy.decide(ranked, context(Instant.parse("2026-09-02T00:20:00Z"))).decision());
    assertEquals(SpeakDecisionStatus.SPEAK,
        policy.decide(ranked, context(Instant.parse("2026-09-02T00:00:00Z"))).decision());
  }

  @Test
  void noCandidatesDoesNotSpeak() {
    assertEquals(SpeakDecisionStatus.DO_NOT_SPEAK,
        new DefaultSpeakDecisionPolicy(new TopicGeneratorProperties()).decide(List.of(), context(null)).decision());
  }

  private SpeakDecisionContext context(Instant lastSpokenAt) {
    return new SpeakDecisionContext(Instant.parse("2026-09-02T00:40:00Z"), lastSpokenAt, List.of(), false, false);
  }

  private TopicCandidate high(String topic) {
    return new TopicCandidate(topic, topic, "", TopicType.FOLLOW_UP, TopicSource.CONVERSATION,
        1, 1, 1, 0, 1, Instant.parse("2026-09-02T00:00:00Z"));
  }

  private TopicCandidate low(String topic) {
    return new TopicCandidate(topic, topic, "", TopicType.FOLLOW_UP, TopicSource.CONVERSATION,
        0.1, 0.1, 0.1, 1, 1, Instant.parse("2026-09-02T00:00:00Z"));
  }
}
