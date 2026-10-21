package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.conversation.SqliteConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Slack完了済みの一回のやり取りの結果と状態の契約を検証するテスト。 */
@Tag("integration")
class SlackCompletedTurnTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** Slackとの連携で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void runLookupIsScopedAndPersistent() throws Exception {
    try (var gate = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    var store = new SqliteConversationTurnStore(root);
    String project = java.util.UUID.randomUUID().toString();
    var a = new AgentRunContext("a", "project:" + project + ":chat:a", root, project);
    var b = new AgentRunContext("b", "project:" + project + ":chat:b", root, project);
    store.start(a, "first");
    store.start(b, "other");
    store.finish(a, ConversationTurnStore.Status.COMPLETED, "final");
    assertEquals(
        "final",
        new SqliteConversationTurnStore(root)
            .findRun(a.conversationId(), "a")
            .orElseThrow()
            .assistantMessage());
    assertTrue(store.findRun(a.conversationId(), "b").isEmpty());
    assertTrue(store.findRun(b.conversationId(), "a").isEmpty());
  }
}
