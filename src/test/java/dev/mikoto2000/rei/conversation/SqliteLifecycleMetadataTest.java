package dev.mikoto2000.rei.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class SqliteLifecycleMetadataTest {
  @TempDir Path root;

  @Test
  void metadataPoliciesMatchLegacyStoreWithLargeBodiesAndUnknownTimesAfterRestart()
      throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
      var sql = new SqliteConversationTurnStore(root);
      var legacy = ConversationTurnStore.inMemory();
      assertThat(sql.cancelledContext("chat")).isEmpty();
      String body = "synthetic conversation body ".repeat(6000);
      var statuses =
          List.of(
              ConversationTurnStore.Status.FAILED,
              ConversationTurnStore.Status.CANCELLED,
              ConversationTurnStore.Status.COMPLETED);
      for (int index = 0; index < statuses.size(); index++) {
        var owner = new AgentRunContext("run-" + index, "chat", root);
        Instant time = index == 1 ? null : Instant.EPOCH.plusSeconds(index);
        for (var store : List.of(sql, legacy)) {
          store.start(owner, body, time);
          store.finish(owner, statuses.get(index), "answer-" + index);
        }
      }
      var restarted = new SqliteConversationTurnStore(root);
      assertThat(restarted.promptHistory("chat")).isEqualTo(legacy.promptHistory("chat"));
      assertThat(restarted.cancelledContext("chat")).isEqualTo(legacy.cancelledContext("chat"));
      assertThat(restarted.cancelledContext("other")).isEmpty();
      assertThat(restarted.read("chat").getFirst().request()).isEqualTo(body);
    }
  }
}
