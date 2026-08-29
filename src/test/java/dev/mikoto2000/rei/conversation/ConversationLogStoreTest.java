package dev.mikoto2000.rei.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

class ConversationLogStoreTest {

  @TempDir
  Path tempDir;

  @Test
  void appendsJsonLinesToDailyFileAndReadsThemBack() throws Exception {
    Clock clock = Clock.fixed(Instant.parse("2026-08-29T03:00:00Z"), ZoneId.of("Asia/Tokyo"));
    ConversationLogStore store = new ConversationLogStore(tempDir, clock,
        new ObjectMapper().registerModule(new JavaTimeModule()));

    store.append("chat:main", "user", "こんにちは");
    store.append("chat:main", "assistant", "どうも");

    Path logFile = tempDir.resolve("2026-08-29.jsonl");
    assertThat(logFile).exists();
    assertThat(Files.readAllLines(logFile)).hasSize(2);
    assertThat(store.readAll()).extracting(ConversationLogEntry::content)
        .containsExactly("こんにちは", "どうも");
  }

  @Test
  void rotatesToAnotherFileWhenTheLocalDateChanges() {
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    new ConversationLogStore(tempDir,
        Clock.fixed(Instant.parse("2026-08-29T14:59:59Z"), ZoneId.of("Asia/Tokyo")), objectMapper)
        .append("chat:main", "user", "日付変更前");
    new ConversationLogStore(tempDir,
        Clock.fixed(Instant.parse("2026-08-29T15:00:00Z"), ZoneId.of("Asia/Tokyo")), objectMapper)
        .append("chat:main", "user", "日付変更後");

    assertThat(tempDir.resolve("2026-08-29.jsonl")).exists();
    assertThat(tempDir.resolve("2026-08-30.jsonl")).exists();
  }
}
