package dev.mikoto2000.rei.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class InMemoryAgentEventBusTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-08-16T16:30:20Z"), ZoneId.of("Asia/Tokyo"));

  private AgentEvent event(String type) {
    return new AgentEvent("id-" + type, 0L, Instant.now(clock), AgentEventType.from(type), 1,
        "session-1", "turn-1", "run-1", (String) null, (String) null, null);
  }

  @Test
  void listenerReceivesPublishedEvent() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> received = new ArrayList<>();
    bus.subscribe(received::add);

    AgentEvent event = event("agent.run.started");
    bus.publish(event);

    assertEquals(1, received.size());
    assertEquals(event.id(), received.get(0).id());
    assertEquals(event.type(), received.get(0).type());
  }

  @Test
  void multipleListenersReceiveEvent() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> first = new ArrayList<>();
    List<AgentEvent> second = new ArrayList<>();
    bus.subscribe(first::add);
    bus.subscribe(second::add);

    AgentEvent event = event("agent.run.started");
    bus.publish(event);

    assertEquals(1, first.size());
    assertEquals(1, second.size());
    assertEquals(event.id(), first.get(0).id());
    assertEquals(event.id(), second.get(0).id());
  }

  @Test
  void unsubscribedListenerDoesNotReceive() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> received = new ArrayList<>();
    AgentEventBus.Subscription subscription = bus.subscribe(received::add);

    subscription.unsubscribe();
    bus.publish(event("agent.run.started"));

    assertTrue(received.isEmpty());
  }

  @Test
  void sequenceIsMonotonicallyIncreasing() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> received = new ArrayList<>();
    bus.subscribe(received::add);

    bus.publish(event("agent.run.started"));
    bus.publish(event("message.started"));
    bus.publish(event("message.completed"));

    assertEquals(3, received.size());
    assertTrue(received.get(0).sequence() < received.get(1).sequence());
    assertTrue(received.get(1).sequence() < received.get(2).sequence());
  }

  @Test
  void sequenceDoesNotDuplicate() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> received = new ArrayList<>();
    bus.subscribe(received::add);

    for (int i = 0; i < 100; i++) {
      bus.publish(event("message.delta"));
    }

    long distinct = received.stream().map(AgentEvent::sequence).distinct().count();
    assertEquals(100, distinct);
  }

  @Test
  void listenerExceptionDoesNotStopOtherListeners() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> received = new ArrayList<>();
    bus.subscribe(e -> {
      throw new RuntimeException("boom");
    });
    bus.subscribe(received::add);

    bus.publish(event("agent.run.started"));

    assertEquals(1, received.size());
  }

  @Test
  void publishAssignsSequence() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> received = new ArrayList<>();
    bus.subscribe(received::add);

    bus.publish(event("agent.run.started"));

    assertNotEquals(0L, received.get(0).sequence());
  }
}
