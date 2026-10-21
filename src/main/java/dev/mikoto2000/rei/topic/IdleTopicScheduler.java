package dev.mikoto2000.rei.topic;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 話題の管理に必要な操作がない状態話題実行予定の管理を扱う。 */
public class IdleTopicScheduler {
  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(IdleTopicScheduler.class);

  /** 操作がない状態話題実行条件。 */
  private final IdleTopicTrigger idleTopicTrigger;

  /** 話題Orchestrator。 */
  private final TopicOrchestrator topicOrchestrator;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 実行状況の通知を生成する窓口。 */
  private final AgentEventFactory eventFactory;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** 直近判断結果キー。 */
  private String lastDecisionKey;

  /** 指定された値や利用先を使って、IdleTopicSchedulerを初期化する。 */
  public IdleTopicScheduler(
      IdleTopicTrigger idleTopicTrigger,
      TopicOrchestrator topicOrchestrator,
      Clock clock,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    this.idleTopicTrigger = idleTopicTrigger;
    this.topicOrchestrator = topicOrchestrator;
    this.clock = clock;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
  }

  /** 話題の管理に関する定期確認を処理する。 */
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

  /** 話題の管理に関するmaybe通知判断結果を処理する。 */
  private void maybePublishDecision(IdleTriggerDecision decision) {
    if (decision.rejectReason() == IdleTriggerRejectReason.FEATURE_DISABLED) return;
    String key = decision.accepted() ? "accepted" : "rejected:" + decision.rejectReason();
    if (!decision.accepted() && key.equals(lastDecisionKey)) return;
    lastDecisionKey = key;
    eventPublisher.publish(
        eventFactory.topicIdleTriggerEvaluated(
            decision.rejectReason(),
            decision.accepted(),
            decision.idleDuration(),
            decision.requiredIdle()));
  }
}
