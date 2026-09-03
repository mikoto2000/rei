package dev.mikoto2000.rei.topic;

import java.time.Instant;

public interface IdleTopicTrigger {
  IdleTriggerDecision evaluate(Instant now);
}
