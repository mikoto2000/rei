package dev.mikoto2000.rei.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class NotificationHistoryIndexTest {
  @TempDir Path root;
  private static final Instant DAY = Instant.parse("2026-10-01T12:00:00Z");

  private ConversationLogStore store(ObjectMapper mapper) {
    return new ConversationLogStore(root, Clock.fixed(DAY, ZoneOffset.UTC), mapper);
  }

  private ObjectMapper mapper() {
    return new ObjectMapper().registerModule(new JavaTimeModule());
  }

  private static final class CountingMapper extends ObjectMapper {
    final AtomicInteger reads = new AtomicInteger();

    CountingMapper() {
      registerModule(new JavaTimeModule());
    }

    @Override
    public <T> T readValue(String value, Class<T> type) throws JsonProcessingException {
      reads.incrementAndGet();
      return super.readValue(value, type);
    }
  }

  private ConversationLogEntry entry(String id, String body) {
    return new ConversationLogEntry(
        "chat",
        "chat",
        "assistant",
        OffsetDateTime.ofInstant(DAY, ZoneOffset.UTC),
        body,
        1,
        ConversationLogStore.BEHAVIOR_NOTIFICATION,
        id,
        Map.of());
  }

  @Test
  void unchangedFilesAreNotReparsedAndOnlyAnAppendedFileIsRebuilt() {
    var writer = store(mapper());
    writer.appendNotification("chat", "one", "first", DAY, Map.of());
    writer.appendNotification("chat", "two", "second", DAY.plusSeconds(86400), Map.of());
    var counter = new CountingMapper();
    var reader = store(counter);
    assertThat(reader.findNotification("missing")).isEmpty();
    assertThat(counter.reads).hasValue(2);
    assertThat(reader.findNotification("missing-again")).isEmpty();
    assertThat(reader.findNotification("one")).isPresent();
    assertThat(counter.reads).hasValue(2);

    writer.appendNotification("chat", "three", "third", DAY.plusSeconds(86400), Map.of());
    assertThat(reader.findNotification("three"))
        .get()
        .extracting(ConversationLogEntry::content)
        .isEqualTo("third");
    assertThat(reader.findNotification("one")).isPresent();
    assertThat(counter.reads).hasValue(4);
  }

  @Test
  void replacementDeletionAndRestartUseTheDurableLogAsSource() throws Exception {
    var json = mapper();
    store(json).appendNotification("chat", "one", "first", DAY, Map.of());
    var reader = store(mapper());
    assertThat(reader.findNotification("one")).isPresent();
    Path file = root.resolve("2026-10-01.jsonl");
    Files.writeString(
        file, json.writeValueAsString(entry("replacement", "a longer replacement body")) + "\n");
    assertThat(reader.findNotification("one")).isEmpty();
    assertThat(reader.findNotification("replacement")).isPresent();
    assertThat(store(mapper()).findNotification("replacement")).isPresent();
    Files.delete(file);
    assertThat(reader.findNotification("replacement")).isEmpty();
  }

  @Test
  void malformedTailPreservesEarlyMatchButMissingLookupStillFailsClosed() throws Exception {
    store(mapper()).appendNotification("chat", "one", "first", DAY, Map.of());
    Files.writeString(root.resolve("2026-10-01.jsonl"), "not-json\n", StandardOpenOption.APPEND);
    var reader = store(mapper());
    assertThat(reader.findNotification("one")).isPresent();
    assertThatThrownBy(() -> reader.findNotification("missing"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot inspect notification history");
    assertThat(reader.findNotification("one")).isPresent();
  }

  @Test
  void concurrentDuplicateAppendsStillWriteOneRecord() throws Exception {
    var writer = store(mapper());
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var futures = new ArrayList<java.util.concurrent.Future<ConversationLogEntry>>();
      for (int index = 0; index < 8; index++) {
        String body = "body-" + index;
        futures.add(
            executor.submit(() -> writer.appendNotification("chat", "same", body, DAY, Map.of())));
      }
      var results = new ArrayList<ConversationLogEntry>();
      for (var future : futures) results.add(future.get(10, java.util.concurrent.TimeUnit.SECONDS));
      assertThat(results.stream().map(ConversationLogEntry::content).distinct().count())
          .isEqualTo(1);
    }
    assertThat(Files.readAllLines(root.resolve("2026-10-01.jsonl"))).hasSize(1);
    var restarted = store(mapper());
    restarted.appendNotification("chat", "same", "must not replace", DAY, Map.of());
    assertThat(Files.readAllLines(root.resolve("2026-10-01.jsonl"))).hasSize(1);
  }

  @Test
  void overCapacityFilesDoNotCreateFalseNegativeLookups() throws Exception {
    var json = mapper();
    var lines = new StringBuilder();
    for (int index = 0; index < 2000; index++)
      lines.append(json.writeValueAsString(entry("id-" + index, "body"))).append('\n');
    Files.writeString(root.resolve("2026-10-01.jsonl"), lines);
    var reader = store(mapper());
    assertThat(reader.findNotification("missing")).isEmpty();
    assertThat(reader.findNotification("id-1999")).isPresent();
  }
}
