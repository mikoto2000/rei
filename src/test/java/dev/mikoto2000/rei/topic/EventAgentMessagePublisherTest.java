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
  void appendsConversationLogAndPublishesMessageEvents() {
    ConversationLogStore logStore = mock(ConversationLogStore.class);
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> events = new ArrayList<>();
    bus.subscribe(events::add);
    DefaultAgentActivityTracker tracker = new DefaultAgentActivityTracker(clock());
    EventAgentMessagePublisher publisher = new EventAgentMessagePublisher(
        logStore, new AgentEventFactory(clock()), bus, tracker);

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
