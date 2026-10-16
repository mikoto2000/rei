package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.Tag("integration")
class BehaviorConversationPublicationTest {
  @TempDir Path directory;
  final Clock clock = Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void behaviorUsesEmissionTimeAndIdempotentAssistantHistoryInsteadOfPlainAppend() {
    var logs =
        new ConversationLogStore(
            directory,
            clock,
            new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
    var bus = new InMemoryAgentEventBus();
    var tracker = new DefaultAgentActivityTracker(clock);
    var publisher =
        new EventAgentMessagePublisher(
            logs,
            new AgentEventFactory(clock),
            bus,
            tracker,
            mock(dev.mikoto2000.rei.ui.shell.sound.AgentMessageNarrator.class));
    var at = clock.instant().minusSeconds(60);
    var message = new AgentMessage("b-001", "assistant", "そろそろ戻ろう。", MessageOrigin.BEHAVIOR, at);
    publisher.publish(message);
    publisher.publish(message);
    var entries = logs.readConversation("chat:main");
    assertEquals(1, entries.size());
    assertEquals("assistant", entries.getFirst().speaker());
    assertEquals(at, entries.getFirst().timestamp().toInstant());
    assertEquals("そろそろ戻ろう。", entries.getFirst().content());
  }
}
