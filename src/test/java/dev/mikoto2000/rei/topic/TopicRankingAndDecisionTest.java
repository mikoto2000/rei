package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 話題順位付けおよび判断結果の結果と状態の契約を検証するテスト。 */
class TopicRankingAndDecisionTest {
  /** 検証に使うranker。 */
  private final DeterministicTopicRanker ranker = new DeterministicTopicRanker();

  /** 話題の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void highScoreCandidateIsSelectedFromMultipleCandidates() {
    var ranked = ranker.rank(List.of(low("low"), high("high")), new TopicRankingContext(List.of()));
    TopicGeneratorProperties properties = new TopicGeneratorProperties();
    DefaultSpeakDecisionPolicy policy = new DefaultSpeakDecisionPolicy(properties);

    SpeakDecision decision = policy.decide(ranked, context(null));

    assertEquals(SpeakDecisionStatus.SPEAK, decision.decision());
    assertEquals("high", decision.selected().candidate().topic());
  }

  /** 話題の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void lowScoreCandidateDoesNotSpeak() {
    TopicGeneratorProperties properties = new TopicGeneratorProperties();
    DefaultSpeakDecisionPolicy policy = new DefaultSpeakDecisionPolicy(properties);

    SpeakDecision decision =
        policy.decide(
            ranker.rank(List.of(low("low")), new TopicRankingContext(List.of())), context(null));

    assertEquals(SpeakDecisionStatus.DO_NOT_SPEAK, decision.decision());
  }

  /** 話題の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void lowConfidenceCandidateDoesNotSpeak() {
    TopicCandidate candidate =
        new TopicCandidate(
            "id",
            "candidate",
            "",
            TopicType.FOLLOW_UP,
            TopicSource.CONVERSATION,
            1,
            1,
            1,
            0,
            0.2,
            Instant.parse("2026-09-02T00:00:00Z"));
    DefaultSpeakDecisionPolicy policy =
        new DefaultSpeakDecisionPolicy(new TopicGeneratorProperties());

    SpeakDecision decision =
        policy.decide(
            ranker.rank(List.of(candidate), new TopicRankingContext(List.of())), context(null));

    assertEquals(SpeakDecisionStatus.DO_NOT_SPEAK, decision.decision());
  }

  /** 話題の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void intrusivenessLowersScore() {
    double calm =
        ranker
            .rank(List.of(high("calm")), new TopicRankingContext(List.of()))
            .getFirst()
            .finalScore();
    TopicCandidate intrusive =
        new TopicCandidate(
            "id",
            "intrusive",
            "",
            TopicType.FOLLOW_UP,
            TopicSource.CONVERSATION,
            1,
            1,
            1,
            1,
            1,
            Instant.parse("2026-09-02T00:00:00Z"));
    double noisy =
        ranker.rank(List.of(intrusive), new TopicRankingContext(List.of())).getFirst().finalScore();

    assertTrue(noisy < calm);
  }

  /** 話題の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void repetitionPenaltyLowersScore() {
    double original =
        ranker
            .rank(List.of(high("same topic")), new TopicRankingContext(List.of()))
            .getFirst()
            .finalScore();
    double repeated =
        ranker
            .rank(List.of(high("same topic")), new TopicRankingContext(List.of("same   topic")))
            .getFirst()
            .finalScore();

    assertTrue(repeated < original);
  }

  /** 話題の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void scoreBreakdownExplainsFinalScore() {
    TopicCandidate candidate =
        new TopicCandidate(
            "id",
            "candidate",
            "",
            TopicType.FOLLOW_UP,
            TopicSource.CONVERSATION,
            0.8,
            0.7,
            0.6,
            0.2,
            0.9,
            Instant.parse("2026-09-02T00:00:00Z"));

    TopicScoreBreakdown score =
        ranker
            .rank(List.of(candidate), new TopicRankingContext(List.of("candidate")))
            .getFirst()
            .score();

    assertEquals(0.16, score.priorityContribution(), 0.0001);
    assertEquals(0.14, score.freshnessContribution(), 0.0001);
    assertEquals(0.18, score.usefulnessContribution(), 0.0001);
    assertEquals(0.18, score.confidenceContribution(), 0.0001);
    assertEquals(0.05, score.intrusivenessPenalty(), 0.0001);
    assertEquals(0.40, score.repetitionPenalty(), 0.0001);
    assertEquals(0.21, score.finalScore(), 0.0001);
  }

  /** 話題の管理で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void cooldownPreventsSpeakingAndAllowsAfterInterval() {
    TopicGeneratorProperties properties = new TopicGeneratorProperties();
    DefaultSpeakDecisionPolicy policy = new DefaultSpeakDecisionPolicy(properties);
    var ranked = ranker.rank(List.of(high("topic")), new TopicRankingContext(List.of()));

    assertEquals(
        SpeakDecisionStatus.DO_NOT_SPEAK,
        policy.decide(ranked, context(Instant.parse("2026-09-02T00:20:00Z"))).decision());
    assertEquals(
        SpeakDecisionStatus.SPEAK,
        policy.decide(ranked, context(Instant.parse("2026-09-02T00:00:00Z"))).decision());
  }

  /** 話題の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void noCandidatesDoesNotSpeak() {
    assertEquals(
        SpeakDecisionStatus.DO_NOT_SPEAK,
        new DefaultSpeakDecisionPolicy(new TopicGeneratorProperties())
            .decide(List.of(), context(null))
            .decision());
  }

  /** 検証で使用する文脈を、指定された条件に合わせて用意する。 */
  private SpeakDecisionContext context(Instant lastSpokenAt) {
    return new SpeakDecisionContext(
        Instant.parse("2026-09-02T00:40:00Z"), lastSpokenAt, List.of(), false, false);
  }

  /** {@code high} の条件を再現するための補助情報を返す。 */
  private TopicCandidate high(String topic) {
    return new TopicCandidate(
        topic,
        topic,
        "",
        TopicType.FOLLOW_UP,
        TopicSource.CONVERSATION,
        1,
        1,
        1,
        0,
        1,
        Instant.parse("2026-09-02T00:00:00Z"));
  }

  /** {@code low} の条件を再現するための補助情報を返す。 */
  private TopicCandidate low(String topic) {
    return new TopicCandidate(
        topic,
        topic,
        "",
        TopicType.FOLLOW_UP,
        TopicSource.CONVERSATION,
        0.1,
        0.1,
        0.1,
        1,
        1,
        Instant.parse("2026-09-02T00:00:00Z"));
  }
}
