package dev.mikoto2000.rei.topic;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.llm.ConversationIds;

@Component
public class EventAgentMessagePublisher implements AgentMessagePublisher {
  private final ConversationLogStore conversationLogStore;
  private final AgentEventFactory eventFactory;
  private final AgentEventPublisher eventPublisher;
  private final AgentActivityTracker activityTracker;

  public EventAgentMessagePublisher(ConversationLogStore conversationLogStore, AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher, AgentActivityTracker activityTracker) {
    this.conversationLogStore = conversationLogStore;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
    this.activityTracker = activityTracker;
  }

  @Override
  public void publish(AgentMessage message) {
    if (message == null || message.content() == null || message.content().isBlank()) return;
    conversationLogStore.append(ConversationIds.chat(), message.role(), message.content());
    eventPublisher.publish(eventFactory.messageStarted(message.id(), message.role()));
    eventPublisher.publish(eventFactory.messageDelta(message.id(), message.content()));
    eventPublisher.publish(eventFactory.messageCompleted(message.id(), message.role(), message.content()));
    if ("assistant".equalsIgnoreCase(message.role())) {
      activityTracker.recordAgentCompleted(message.createdAt());
    }
  }
}
