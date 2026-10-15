package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.*;

import dev.mikoto2000.rei.conversation.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class SlackCompletedTurnTest {
  @TempDir Path root;

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
