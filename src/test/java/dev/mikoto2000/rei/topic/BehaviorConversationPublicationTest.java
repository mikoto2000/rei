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

/** 作業習慣会話公開情報の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class BehaviorConversationPublicationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Clock clock = Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneOffset.UTC);

  /** 話題の管理で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
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
