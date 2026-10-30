package dev.mikoto2000.rei.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class ConversationHistoryCacheTest {
  @TempDir Path root;

  private static final class CountingMapper extends ObjectMapper {
    final AtomicInteger reads = new AtomicInteger();

    CountingMapper() {
      registerModule(new JavaTimeModule());
    }

    @Override
    public <T> T readValue(String line, Class<T> type) throws JsonProcessingException {
      reads.incrementAndGet();
      return super.readValue(line, type);
    }
  }

  private String line(ObjectMapper mapper, long sequence, String body) throws Exception {
    return mapper.writeValueAsString(
            new ConversationLogEntry(
                "chat",
                "chat",
                "assistant",
                OffsetDateTime.parse("2026-10-01T12:00:00Z").plusSeconds(sequence),
                body,
                sequence))
        + "\n";
  }

  @Test
  void unchangedFilesAreReusedButAppendReplacementAndDeletionAreObserved() throws Exception {
    var mapper = new CountingMapper();
    var first = root.resolve("2026-10-01.jsonl");
    var second = root.resolve("2026-10-02.jsonl");
    Files.writeString(first, line(mapper, 1, "one"));
    Files.writeString(second, line(mapper, 2, "two"));
    var store = new ConversationLogStore(root, Clock.systemUTC(), mapper);
    assertThat(store.readConversation("chat")).hasSize(2);
    assertThat(mapper.reads).hasValue(2);
    assertThat(store.readConversation("chat")).hasSize(2);
    assertThat(mapper.reads).hasValue(2);
    Files.writeString(second, line(mapper, 3, "three"), java.nio.file.StandardOpenOption.APPEND);
    assertThat(store.readConversation("chat")).hasSize(3);
    assertThat(mapper.reads).hasValue(4);
    Files.writeString(first, line(mapper, 1, "replacement longer than original"));
    assertThat(store.readConversation("chat").getFirst().content()).startsWith("replacement");
    assertThat(mapper.reads).hasValue(5);
    Files.delete(first);
    assertThat(store.readConversation("chat"))
        .extracting(ConversationLogEntry::content)
        .containsExactly("two", "three");
  }

  @Test
  void oversizedFilesBypassTheCacheAndLegacyOrderRemainsStable() throws Exception {
    var mapper = new CountingMapper();
    Files.writeString(root.resolve("large.jsonl"), line(mapper, 0, "x".repeat(1024 * 1024 + 1)));
    var store = new ConversationLogStore(root, Clock.systemUTC(), mapper);
    assertThat(store.readConversation("chat").getFirst().sequence()).isEqualTo(1);
    assertThat(store.readConversation("chat").getFirst().sequence()).isEqualTo(1);
    assertThat(mapper.reads).hasValue(2);
  }
}
