package dev.mikoto2000.rei.topic;

public enum TopicSpeakSkipReason {
  COOLDOWN,
  USER_ACTIVE,
  AGENT_BUSY,
  BELOW_THRESHOLD,
  LOW_CONFIDENCE,
  NO_CANDIDATE,
  FEATURE_DISABLED
}
