package dev.mikoto2000.rei.episode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.conversation.SqliteConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.SqliteProjectAgentEventStore;
import dev.mikoto2000.rei.event.ToolCompletedPayload;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 経験の記録Indexed取得元一覧の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class EpisodeIndexedSourcesTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void prepare() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
  }

  /** 過去の経験の活用で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void rangesAndRunLookupSurviveRestartWithoutReadingEarlierRecords() {
    var store = new SqliteConversationTurnStore(root);
    for (int i = 0; i < 100; i++) {
      var owner = new AgentRunContext("r" + i, "chat:fixture", root);
      store.start(owner, "request " + i, Instant.EPOCH.plusSeconds(i));
      store.finish(owner, ConversationTurnStore.Status.COMPLETED, "result " + i);
    }
    var restarted = new SqliteConversationTurnStore(root);
    assertEquals(100, restarted.turnCount("chat:fixture"));
    assertEquals(
        java.util.List.of("r98", "r99"),
        restarted.readRange("chat:fixture", 98, 50).stream()
            .map(ConversationTurnStore.Turn::runId)
            .toList());
    assertEquals(
        "result 50", restarted.findRun("chat:fixture", "r50").orElseThrow().assistantMessage());
    assertTrue(restarted.findRun("chat:other", "r50").isEmpty());
  }

  /** 過去の経験の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void exactEventLookupDoesNotDependOnRecentWindowAndRespectsProject() {
    String project = java.util.UUID.randomUUID().toString();
    var store = new SqliteProjectAgentEventStore(root);
    var first =
        store.append(
            new AgentEvent(
                "event1",
                0,
                Instant.EPOCH,
                AgentEventType.TOOL_COMPLETED,
                1,
                "s",
                "r",
                "r",
                null,
                null,
                new ToolCompletedPayload("call", "test", 1, "passed", null, null, null, null),
                project));
    store.append(
        new AgentEvent(
            "event2",
            0,
            Instant.EPOCH.plusSeconds(1),
            AgentEventType.TOOL_COMPLETED,
            1,
            "s",
            "r2",
            "r2",
            null,
            null,
            new ToolCompletedPayload("call2", "test", 1, "passed", null, null, null, null),
            project));
    assertEquals("event2", store.recent(project, 1).getFirst().id());
    assertEquals(first, store.findEvent(project, "event1").orElseThrow());
    assertTrue(store.findEvent(java.util.UUID.randomUUID().toString(), "event1").isEmpty());
  }
}
