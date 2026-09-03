package dev.mikoto2000.rei.topic;

import java.time.Instant;

public record SpeakDecision(SpeakDecisionStatus decision, TopicSpeakSkipReason reason,
    RankedTopicCandidate selected, Instant nextSpeakAllowedAt) {
  public static SpeakDecision speak(RankedTopicCandidate selected, String reason) {
    return new SpeakDecision(SpeakDecisionStatus.SPEAK, null, selected, null);
  }

  public static SpeakDecision doNotSpeak(TopicSpeakSkipReason reason) {
    return new SpeakDecision(SpeakDecisionStatus.DO_NOT_SPEAK, reason, null, null);
  }

  public static SpeakDecision doNotSpeak(TopicSpeakSkipReason reason, Instant nextSpeakAllowedAt) {
    return new SpeakDecision(SpeakDecisionStatus.DO_NOT_SPEAK, reason, null, nextSpeakAllowedAt);
  }
}
