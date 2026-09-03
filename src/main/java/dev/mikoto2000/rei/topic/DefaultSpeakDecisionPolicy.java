package dev.mikoto2000.rei.topic;

import java.time.Duration;
import java.util.List;

public class DefaultSpeakDecisionPolicy implements SpeakDecisionPolicy {
  private final TopicGeneratorProperties properties;

  public DefaultSpeakDecisionPolicy(TopicGeneratorProperties properties) {
    this.properties = properties;
  }

  @Override
  public SpeakDecision decide(List<RankedTopicCandidate> candidates, SpeakDecisionContext context) {
    if (candidates == null || candidates.isEmpty()) return SpeakDecision.doNotSpeak(TopicSpeakSkipReason.NO_CANDIDATE);
    if (context.agentBusy()) return SpeakDecision.doNotSpeak(TopicSpeakSkipReason.AGENT_BUSY);
    if (context.userRecentlyActive()) return SpeakDecision.doNotSpeak(TopicSpeakSkipReason.USER_ACTIVE);
    if (context.lastTopicSpokenAt() != null) {
      Duration elapsed = Duration.between(context.lastTopicSpokenAt(), context.currentTime());
      if (elapsed.compareTo(properties.getMinimumTopicSpeakInterval()) < 0) {
        return SpeakDecision.doNotSpeak(TopicSpeakSkipReason.COOLDOWN,
            context.lastTopicSpokenAt().plus(properties.getMinimumTopicSpeakInterval()));
      }
    }
    RankedTopicCandidate top = candidates.getFirst();
    if (top.finalScore() < properties.getMinimumScore()) return SpeakDecision.doNotSpeak(TopicSpeakSkipReason.BELOW_THRESHOLD);
    if (top.candidate().confidence() < properties.getMinimumConfidence()) {
      return SpeakDecision.doNotSpeak(TopicSpeakSkipReason.LOW_CONFIDENCE);
    }
    if (context.recentTopics().stream()
        .map(DeterministicTopicRanker::normalize)
        .anyMatch(value -> value.equals(DeterministicTopicRanker.normalize(top.candidate().topic())))) {
      return SpeakDecision.doNotSpeak(TopicSpeakSkipReason.NO_CANDIDATE);
    }
    return SpeakDecision.speak(top, "selected");
  }
}
