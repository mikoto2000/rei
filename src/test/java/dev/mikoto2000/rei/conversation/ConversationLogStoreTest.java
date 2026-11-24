package dev.mikoto2000.rei.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 会話ログ保存先の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ConversationLogStoreTest {
  @Test
  void internalProtocolHistoryRetainsOriginalSequencesWithoutAddingDisplayMessages() {
    var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    var store = new ConversationLogStore(tempDir, Clock.systemUTC(), mapper);
    store.append("chat", "user", "display request");
    var pointers = java.util.List.of(
        java.util.Map.of("rei.modelMessage.id", "first", "rei.modelMessage.sha256", "a".repeat(64),
            "rei.modelMessage.historyOwner", "turn:run", "rei.modelMessage.run", "run",
            "rei.modelMessage.role", "user"),
        java.util.Map.of("rei.modelMessage.id", "last", "rei.modelMessage.sha256", "b".repeat(64),
            "rei.modelMessage.historyOwner", "turn:run", "rei.modelMessage.run", "run",
            "rei.modelMessage.role", "assistant"));
    var committed = store.appendProtocolHistory("chat", "run", pointers);
    assertThat(committed).containsAllEntriesOf(java.util.Map.of(
        ConversationLogStore.PROTOCOL_VERSION, "1", ConversationLogStore.PROTOCOL_FIRST, "2",
        ConversationLogStore.PROTOCOL_LAST, "3", ConversationLogStore.PROTOCOL_COUNT, "2",
        "rei.modelMessage.run", "run"));
    store.append("chat", "assistant", "display answer");
    var restarted = new ConversationLogStore(tempDir, Clock.systemUTC(), mapper);
    assertThat(restarted.appendProtocolHistory("chat", "run", pointers))
        .as("retry after restart reuses the exact committed protocol sequence range").isEqualTo(committed);
    assertThat(restarted.readAll()).extracting(ConversationLogEntry::content)
        .containsExactly("display request", "display answer");
    assertThat(restarted.readConversation("chat")).extracting(ConversationLogEntry::sequence)
        .containsExactly(1L, 2L, 3L, 4L);
    assertThat(restarted.readConversation("chat").stream()
        .filter(entry -> ConversationLogStore.PROTOCOL_SPEAKER.equals(entry.speaker())).toList())
        .extracting(ConversationLogEntry::metadata).containsExactlyElementsOf(pointers);
  }

  @Test
  void pointerMetadataAndBlankProtocolMessageSurviveRestart() {
    var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    var metadata =
        java.util.Map.of(
            "rei.modelMessage.id",
            "fixture-id",
            "rei.modelMessage.sha256",
            "a".repeat(64),
            "rei.modelMessage.historyOwner",
            "turn:fixture");
    var store = new ConversationLogStore(tempDir, Clock.systemUTC(), mapper);
    store.append("chat", "assistant", "", metadata);
    var entry =
        new ConversationLogStore(tempDir, Clock.systemUTC(), mapper)
            .readConversation("chat")
            .getFirst();
    assertThat(entry.content()).isEmpty();
    assertThat(entry.metadata()).isEqualTo(metadata);
    assertThat(entry.sequence()).isEqualTo(1);
  }

  @Test
  void durablePointerWriteFailureCannotBeReportedAsSuccess() throws Exception {
    Path blocked = tempDir.resolve("blocked");
    Files.writeString(blocked, "fixture file blocks directory creation");
    var store =
        new ConversationLogStore(
            blocked, Clock.systemUTC(), new ObjectMapper().registerModule(new JavaTimeModule()));
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            store.append(
                "chat",
                "assistant",
                "fixture",
                java.util.Map.of("rei.modelMessage.id", "fixture-id")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Cannot persist conversation log reference")
        .hasNoCause();
  }

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** 文脈発生順序について、再開の後も対象の情報が残ることを確認する。 */
  @Test
  void contextSequenceSurvivesRestartAndClockRollback() {
    var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    var first =
        new ConversationLogStore(
            tempDir, Clock.fixed(Instant.parse("2026-09-18T00:00:00Z"), ZoneId.of("UTC")), mapper);
    first.append("chat", "user", "first");
    var second =
        new ConversationLogStore(
            tempDir, Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneId.of("UTC")), mapper);
    second.append("chat", "user", "second");
    assertThat(second.readConversation("chat"))
        .extracting(ConversationLogEntry::content)
        .containsExactly("first", "second");
    assertThat(second.readConversation("chat"))
        .extracting(ConversationLogEntry::sequence)
        .containsExactly(1L, 2L);
  }

  /** 旧形式ログ一覧音量の補正について、続きの取得位置一覧を読み取ることを確認する。 */
  @Test
  void legacyLogsGainReadCursorsWithoutRewritingTheirContents() throws Exception {
    String raw =
        "{\"conversationId\":\"chat\",\"scope\":\"chat\",\"speaker\":\"user\","
            + "\"timestamp\":\"2026-09-18T00:00:00Z\",\"content\":\"legacy requirement\"}\n";
    Path original = tempDir.resolve("2026-09-18.jsonl");
    Files.writeString(original, raw);
    var store =
        new ConversationLogStore(
            tempDir,
            Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneId.of("UTC")),
            new ObjectMapper().registerModule(new JavaTimeModule()));
    store.append("chat", "user", "new requirement");
    assertThat(store.readConversation("chat"))
        .extracting(ConversationLogEntry::content)
        .containsExactly("legacy requirement", "new requirement");
    assertThat(store.readConversation("chat"))
        .extracting(ConversationLogEntry::sequence)
        .containsExactly(1L, 2L);
    assertThat(Files.readString(original)).isEqualTo(raw);
  }

  /** 会話履歴と受付記録の管理で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void appendsJsonLinesToDailyFileAndReadsThemBack() throws Exception {
    Clock clock = Clock.fixed(Instant.parse("2026-08-29T03:00:00Z"), ZoneId.of("Asia/Tokyo"));
    ConversationLogStore store =
        new ConversationLogStore(
            tempDir, clock, new ObjectMapper().registerModule(new JavaTimeModule()));

    store.append("chat:main", "user", "こんにちは");
    store.append("chat:main", "assistant", "どうも");

    Path logFile = tempDir.resolve("2026-08-29.jsonl");
    assertThat(logFile).exists();
    assertThat(Files.readAllLines(logFile)).hasSize(2);
    assertThat(store.readAll())
        .extracting(ConversationLogEntry::content)
        .containsExactly("こんにちは", "どうも");
  }

  /** 会話履歴と受付記録の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void rotatesToAnotherFileWhenTheLocalDateChanges() {
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    new ConversationLogStore(
            tempDir,
            Clock.fixed(Instant.parse("2026-08-29T14:59:59Z"), ZoneId.of("Asia/Tokyo")),
            objectMapper)
        .append("chat:main", "user", "日付変更前");
    new ConversationLogStore(
            tempDir,
            Clock.fixed(Instant.parse("2026-08-29T15:00:00Z"), ZoneId.of("Asia/Tokyo")),
            objectMapper)
        .append("chat:main", "user", "日付変更後");

    assertThat(tempDir.resolve("2026-08-29.jsonl")).exists();
    assertThat(tempDir.resolve("2026-08-30.jsonl")).exists();
  }
}
