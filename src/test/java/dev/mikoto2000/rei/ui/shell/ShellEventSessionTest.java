package dev.mikoto2000.rei.ui.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;

class ShellEventSessionTest {
  @Test
  void pausesForTuiResumesForShellAndUnsubscribesOnClose() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    AgentEventFactory events = new AgentEventFactory(
        Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC));
    AtomicInteger received = new AtomicInteger();
    ShellEventSession session = new ShellEventSession(bus, event -> received.incrementAndGet());

    bus.publish(events.runStarted("r1", "user", null));
    session.pause();
    bus.publish(events.runStarted("r2", "user", null));
    session.resume();
    bus.publish(events.runStarted("r3", "user", null));
    session.close();
    bus.publish(events.runStarted("r4", "user", null));

    assertEquals(2, received.get());
  }
}
