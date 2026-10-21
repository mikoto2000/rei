package dev.mikoto2000.rei.memory.service;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.MemoryLifecyclePayload;
import org.springframework.stereotype.Component;

/** 長期記憶の保存と検索に必要な記憶イベント一覧を扱う。 */
@Component
public class MemoryEvents {
  /** 生成元。 */
  private final AgentEventFactory factory;

  /** 通知先。 */
  private final AgentEventPublisher publisher;

  /** 指定された値や利用先を使って、MemoryEventsを初期化する。 */
  public MemoryEvents(AgentEventFactory factory, AgentEventPublisher publisher) {
    this.factory = factory;
    this.publisher = publisher;
  }

  /** 状態や結果を購読先へ通知する。 */
  public void publish(
      AgentEventType type,
      String project,
      String session,
      String id,
      boolean preview,
      int turns,
      int candidates,
      int memories,
      String status) {
    try {
      var event =
          factory.memory(
              type,
              project,
              session,
              new MemoryLifecyclePayload(id, preview, turns, candidates, memories, status));
      if (type == AgentEventType.MEMORY_RETRIEVAL_COMPLETED)
        event = event.withOwnership(dev.mikoto2000.rei.core.chat.AgentRunScope.current());
      publisher.publish(event);
    } catch (RuntimeException error) {
      // Observability must not change the outcome of an already committed transaction.
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Memory event delivery failed ({})", error.getClass().getSimpleName());
    }
  }
}
