package dev.mikoto2000.rei.topic;

import dev.mikoto2000.rei.conversation.BehaviorConversationHistoryAppender;
import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.llm.ConversationIds;
import org.springframework.stereotype.Component;

/** 話題の管理に必要なイベントエージェントメッセージ通知先を扱う。 */
@Component
public class EventAgentMessagePublisher implements AgentMessagePublisher {
  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(EventAgentMessagePublisher.class);

  /** 会話ログ保存先。 */
  private final ConversationLogStore conversationLogStore;

  /** 実行状況の通知を生成する窓口。 */
  private final AgentEventFactory eventFactory;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** 作業状況追跡。 */
  private final AgentActivityTracker activityTracker;

  /** 読み上げの案内。 */
  private final dev.mikoto2000.rei.ui.shell.sound.AgentMessageNarrator narrator;

  /** 作業習慣履歴。 */
  private BehaviorConversationHistoryAppender behaviorHistory;

  /** 話題の管理に関する作業習慣履歴を処理する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void behaviorHistory(BehaviorConversationHistoryAppender history) {
    this.behaviorHistory = history;
  }

  /** 指定された値や利用先を使って、EventAgentMessagePublisherを初期化する。 */
  public EventAgentMessagePublisher(
      ConversationLogStore conversationLogStore,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher,
      AgentActivityTracker activityTracker,
      dev.mikoto2000.rei.ui.shell.sound.AgentMessageNarrator narrator) {
    this.conversationLogStore = conversationLogStore;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
    this.activityTracker = activityTracker;
    this.narrator = narrator;
    this.behaviorHistory =
        new BehaviorConversationHistoryAppender(conversationLogStore, null, null, null);
  }

  /** 状態や結果を購読先へ通知する。 */
  @Override
  public synchronized void publish(AgentMessage message) {
    synchronized (behaviorHistory.publicationLock()) {
      publishLocked(message);
    }
  }

  /** 実行状況を利用先で把握できるよう、使用中を通知する。 */
  private void publishLocked(AgentMessage message) {
    if (message == null || message.content() == null || message.content().isBlank()) return;
    boolean behavior = message.origin() == MessageOrigin.BEHAVIOR;
    BehaviorConversationHistoryAppender.Destination destination = null;
    if (behavior) {
      try {
        destination = behaviorHistory.capture(message);
      } catch (RuntimeException error) {
        log.warn("Behavior conversation destination unavailable; notification continues");
      }
      if (destination != null && destination.alreadyRecorded()) {
        append(destination, message);
        return;
      }
    } else conversationLogStore.append(ConversationIds.chat(), message.role(), message.content());
    eventPublisher.publish(
        owned(eventFactory.messageStarted(message.id(), message.role()), message, destination));
    eventPublisher.publish(
        owned(eventFactory.messageDelta(message.id(), message.content()), message, destination));
    eventPublisher.publish(
        owned(
            eventFactory.messageCompleted(message.id(), message.role(), message.content()),
            message,
            destination));
    if (behavior && destination != null) append(destination, message);
    if ("assistant".equalsIgnoreCase(message.role()))
      activityTracker.recordAgentCompleted(message.createdAt());
    if (behavior) {
      try {
        narrator.onPublished(message);
      } catch (RuntimeException error) {
        log.warn("Behavior narration failed after message emission");
      }
    } else narrator.onPublished(message);
  }

  /** 既存の記録へ経緯を残すため、イベントエージェントメッセージ通知先を追記する。 */
  private void append(
      BehaviorConversationHistoryAppender.Destination destination, AgentMessage message) {
    try {
      behaviorHistory.append(destination, message);
      log.debug("Behavior conversation projection completed: id={}", message.id());
    } catch (RuntimeException error) {
      log.warn("Behavior conversation persistence failed after emission: id={}", message.id());
    }
  }

  /** 話題の管理で用いる自分が所有する対象を返す。 */
  private AgentEvent owned(
      AgentEvent event,
      AgentMessage message,
      BehaviorConversationHistoryAppender.Destination destination) {
    if (destination == null) return event;
    return new AgentEvent(
        event.id(),
        event.sequence(),
        message.createdAt(),
        event.type(),
        event.version(),
        destination.conversationId(),
        null,
        null,
        message.id(),
        event.parentEventId(),
        event.payload(),
        destination.projectId());
  }
}
