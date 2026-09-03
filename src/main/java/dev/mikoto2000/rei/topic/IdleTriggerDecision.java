package dev.mikoto2000.rei.topic;

import java.time.Duration;

public record IdleTriggerDecision(
    boolean accepted,
    Duration idleDuration,
    IdleTriggerRejectReason rejectReason,
    Duration requiredIdle) {

  public static IdleTriggerDecision accepted(Duration idleDuration, Duration requiredIdle) {
    return new IdleTriggerDecision(true, idleDuration, null, requiredIdle);
  }

  public static IdleTriggerDecision rejected(Duration idleDuration, IdleTriggerRejectReason reason,
      Duration requiredIdle) {
    return new IdleTriggerDecision(false, idleDuration, reason, requiredIdle);
  }
}
