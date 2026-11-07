package dev.mikoto2000.rei.topic;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** 話題の管理に必要な既定操作がない状態話題実行条件を扱う。 */
public class DefaultIdleTopicTrigger implements IdleTopicTrigger {
  /** この機能の動作設定。 */
  private final Supplier<TopicSettings> settings;

  /** 作業状況追跡。 */
  private final AgentActivityTracker activityTracker;

  /** 指定された値や利用先を使って、DefaultIdleTopicTriggerを初期化する。 */
  public DefaultIdleTopicTrigger(
      TopicGeneratorProperties properties, AgentActivityTracker activityTracker) {
    this.settings = () -> TopicSettings.capture(properties);
    this.activityTracker = activityTracker;
  }

  /** 対象を判断基準に照らして評価する。 */
  @Override
  public IdleTriggerDecision evaluate(Instant now) {
    var topicSettings = settings.get();
    Instant latestActivityAt =
        Stream.of(
                activityTracker.applicationStartedAt(),
                activityTracker.lastUserActivityAt(),
                activityTracker.lastAgentActivityAt())
            .filter(java.util.Objects::nonNull)
            .max(Instant::compareTo)
            .orElse(now);
    Duration idleDuration = Duration.between(latestActivityAt, now);
    Duration required = topicSettings.minimumIdle();
    if (!topicSettings.enabled()) {
      return IdleTriggerDecision.rejected(
          idleDuration, IdleTriggerRejectReason.FEATURE_DISABLED, required);
    }
    if (!topicSettings.idleEnabled()) {
      return IdleTriggerDecision.rejected(
          idleDuration, IdleTriggerRejectReason.IDLE_TRIGGER_DISABLED, required);
    }
    if (activityTracker.isAgentBusy()) {
      return IdleTriggerDecision.rejected(
          idleDuration, IdleTriggerRejectReason.AGENT_BUSY, required);
    }
    if (idleDuration.compareTo(required) < 0) {
      return IdleTriggerDecision.rejected(
          idleDuration, IdleTriggerRejectReason.INSUFFICIENT_IDLE, required);
    }
    return IdleTriggerDecision.accepted(idleDuration, required);
  }
}
