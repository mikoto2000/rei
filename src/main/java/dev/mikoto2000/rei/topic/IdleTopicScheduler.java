package dev.mikoto2000.rei.topic;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;

public class IdleTopicScheduler {
  private static final Logger log = LoggerFactory.getLogger(IdleTopicScheduler.class);

  private final IdleTopicTrigger idleTopicTrigger;
  private final TopicOrchestrator topicOrchestrator;
  private final Clock clock;
  private final AgentEventFactory eventFactory;
  private final AgentEventPublisher eventPublisher;
  private String lastDecisionKey;

  public IdleTopicScheduler(IdleTopicTrigger idleTopicTrigger, TopicOrchestrator topicOrchestrator, Clock clock,
      AgentEventFactory eventFactory, AgentEventPublisher eventPublisher) {
    this.idleTopicTrigger = idleTopicTrigger;
    this.topicOrchestrator = topicOrchestrator;
    this.clock = clock;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
  }

  @Scheduled(fixedDelayString = "${rei.topic-generator.idle-trigger.check-interval:30s}")
  public void tick() {
    try {
      IdleTriggerDecision decision = idleTopicTrigger.evaluate(clock.instant());
      maybePublishDecision(decision);
      if (!decision.accepted()) return;
      topicOrchestrator.onUserIdle();
    } catch (RuntimeException exception) {
      log.warn("Idle topic scheduler tick failed", exception);
    }
  }

  private void maybePublishDecision(IdleTriggerDecision decision) {
    if (decision.rejectReason() == IdleTriggerRejectReason.FEATURE_DISABLED) return;
    String key = decision.accepted() ? "accepted" : "rejected:" + decision.rejectReason();
    if (!decision.accepted() && key.equals(lastDecisionKey)) return;
    lastDecisionKey = key;
    eventPublisher.publish(eventFactory.topicIdleTriggerEvaluated(decision.rejectReason(), decision.accepted(),
        decision.idleDuration(), decision.requiredIdle()));
  }
}
