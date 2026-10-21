package dev.mikoto2000.rei.topic;

import java.time.Duration;
import java.util.List;

/** 既定読み上げ判断結果の判断基準をまとめる。 */
public class DefaultSpeakDecisionPolicy implements SpeakDecisionPolicy {
  /** この機能の動作設定。 */
  private final TopicGeneratorProperties properties;

  /** 指定された値や利用先を使って、DefaultSpeakDecisionPolicyを初期化する。 */
  public DefaultSpeakDecisionPolicy(TopicGeneratorProperties properties) {
    this.properties = properties;
  }

  /** 話題の管理で用いる判断を返す。 */
  @Override
  public SpeakDecision decide(List<RankedTopicCandidate> candidates, SpeakDecisionContext context) {
    if (candidates == null || candidates.isEmpty())
      return SpeakDecision.doNotSpeak(TopicSpeakSkipReason.NO_CANDIDATE);
    if (context.agentBusy()) return SpeakDecision.doNotSpeak(TopicSpeakSkipReason.AGENT_BUSY);
    if (context.userRecentlyActive())
      return SpeakDecision.doNotSpeak(TopicSpeakSkipReason.USER_ACTIVE);
    if (context.lastTopicSpokenAt() != null) {
      Duration elapsed = Duration.between(context.lastTopicSpokenAt(), context.currentTime());
      if (elapsed.compareTo(properties.getMinimumTopicSpeakInterval()) < 0) {
        return SpeakDecision.doNotSpeak(
            TopicSpeakSkipReason.COOLDOWN,
            context.lastTopicSpokenAt().plus(properties.getMinimumTopicSpeakInterval()));
      }
    }
    RankedTopicCandidate top = candidates.getFirst();
    if (top.finalScore() < properties.getMinimumScore())
      return SpeakDecision.doNotSpeak(TopicSpeakSkipReason.BELOW_THRESHOLD);
    if (top.candidate().confidence() < properties.getMinimumConfidence()) {
      return SpeakDecision.doNotSpeak(TopicSpeakSkipReason.LOW_CONFIDENCE);
    }
    if (context.recentTopics().stream()
        .map(DeterministicTopicRanker::normalize)
        .anyMatch(
            value -> value.equals(DeterministicTopicRanker.normalize(top.candidate().topic())))) {
      return SpeakDecision.doNotSpeak(TopicSpeakSkipReason.NO_CANDIDATE);
    }
    return SpeakDecision.speak(top, "selected");
  }
}
