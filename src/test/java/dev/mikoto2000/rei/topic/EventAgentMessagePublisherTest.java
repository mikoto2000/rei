package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.llm.ConversationIds;

class EventAgentMessagePublisherTest {
  @Test
  void behaviorNarrationStartsOnlyAfterSuccessfulDelivery() {
    var logStore=mock(ConversationLogStore.class);
    var bus=mock(dev.mikoto2000.rei.event.AgentEventPublisher.class);
    var narrator=mock(dev.mikoto2000.rei.ui.shell.sound.AgentMessageNarrator.class);
    var publisher=new EventAgentMessagePublisher(logStore,new AgentEventFactory(clock()),bus,
        new DefaultAgentActivityTracker(clock()),narrator);
    var message=new AgentMessage("behavior","assistant","休憩しましょう",MessageOrigin.BEHAVIOR,clock().instant());
    publisher.publish(message);
    var order=org.mockito.Mockito.inOrder(bus,narrator);
    order.verify(bus,org.mockito.Mockito.times(3)).publish(org.mockito.ArgumentMatchers.any());
    order.verify(narrator).onPublished(message);
    org.mockito.Mockito.reset(narrator);
    org.mockito.Mockito.doThrow(new IllegalStateException()).when(logStore).append(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
    org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->publisher.publish(message));
    org.mockito.Mockito.verifyNoInteractions(narrator);
  }

  @Test
  void appendsConversationLogAndPublishesMessageEvents() {
    ConversationLogStore logStore = mock(ConversationLogStore.class);
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> events = new ArrayList<>();
    bus.subscribe(events::add);
    DefaultAgentActivityTracker tracker = new DefaultAgentActivityTracker(clock());
    EventAgentMessagePublisher publisher = new EventAgentMessagePublisher(
        logStore, new AgentEventFactory(clock()), bus, tracker,
        mock(dev.mikoto2000.rei.ui.shell.sound.AgentMessageNarrator.class));

    Instant createdAt = Instant.parse("2026-09-02T00:01:00Z");
    publisher.publish(new AgentMessage("message-id", "assistant", "hello", MessageOrigin.TOPIC_GENERATOR, createdAt));

    verify(logStore).append(ConversationIds.chat(), "assistant", "hello");
    assertEquals(List.of(
        AgentEventType.MESSAGE_STARTED,
        AgentEventType.MESSAGE_DELTA,
        AgentEventType.MESSAGE_COMPLETED), events.stream().map(AgentEvent::type).toList());
    assertEquals(createdAt, tracker.lastAgentActivityAt());
  }

  private Clock clock() {
    return Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);
  }
}
