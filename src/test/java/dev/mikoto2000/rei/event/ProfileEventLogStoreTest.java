package dev.mikoto2000.rei.event;

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

/** 利用者情報イベントログ保存先の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ProfileEventLogStoreTest {

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  private final Clock clock =
      Clock.fixed(Instant.parse("2026-08-16T16:30:20Z"), ZoneId.of("Asia/Tokyo"));

  /** 通知された出来事と順序を検証するための記録。 */
  private final AgentEventFactory events = new AgentEventFactory(clock);

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** 追記について、JSON変換行一覧を書き込むことを確認する。 */
  @Test
  void appendWritesJsonLines() throws Exception {
    Path file = tempDir.resolve(".rei").resolve("profile.log");
    ProfileEventLogStore store = store(file);

    store.append(events.runStarted("run-1", "chat", null));
    store.append(events.runCompleted("run-1", 123L, 10L, 20.0, 4.0, 3.0));

    assertThat(Files.readAllLines(file)).hasSize(2);
    assertThat(store.readAll()).hasSize(2);
    assertThat(store.summarize().countsByType()).containsEntry("agent.run.completed", 1L);
    assertThat(store.summarize().durationsByType().get("agent.run.completed").averageMillis())
        .isEqualTo(123L);
  }

  /** 実行状況の通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void textPayloadIsStoredAsLength() {
    Path file = tempDir.resolve(".rei").resolve("profile.log");
    ProfileEventLogStore store = store(file);

    store.append(events.messageCompleted("message-1", "assistant", "hello"));

    ProfileEventLogEntry entry = store.readAll().getFirst();
    assertThat(entry.payload()).containsEntry("textLength", 5);
    assertThat(entry.payload()).doesNotContainKey("text");
  }

  /** 実行状況の通知で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void bucketsCountsEventsByTimeWindow() {
    Path file = tempDir.resolve(".rei").resolve("profile.log");
    ProfileEventLogStore store = store(file);

    store.append(eventAt("id-1", Instant.parse("2026-08-16T16:30:20Z")));
    store.append(eventAt("id-2", Instant.parse("2026-08-16T16:30:25Z")));
    store.append(eventAt("id-3", Instant.parse("2026-08-16T16:31:20Z")));

    assertThat(store.buckets(java.time.Duration.ofSeconds(60)))
        .extracting(ProfileEventLogStore.ProfileBucket::count)
        .containsExactly(2L, 1L);
  }

  /** 検証で使用する保存先を、指定された条件に合わせて用意する。 */
  private ProfileEventLogStore store(Path file) {
    return new ProfileEventLogStore(file, new ObjectMapper().registerModule(new JavaTimeModule()));
  }

  /** {@code eventAt} の条件を再現するための補助情報を返す。 */
  private AgentEvent eventAt(String id, Instant timestamp) {
    return new AgentEvent(
        id,
        0L,
        timestamp,
        AgentEventType.MESSAGE_STARTED,
        1,
        null,
        null,
        null,
        null,
        null,
        new MessageStartedPayload(id, "assistant"));
  }
}
