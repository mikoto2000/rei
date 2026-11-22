package dev.mikoto2000.rei.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import dev.mikoto2000.rei.storage.StorageDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
      var notification =
          new ConversationLogEntry(
              "chat", "chat", "assistant", Instant.EPOCH.plusSeconds(3).atOffset(java.time.ZoneOffset.UTC),
              "generated routine notification", 1, ConversationLogStore.BEHAVIOR_NOTIFICATION,
              "notification", java.util.Map.of());
      sql.appendAssistantNotification(notification);
      legacy.appendAssistantNotification(notification);
      var restarted = new SqliteConversationTurnStore(root);
      assertThat(restarted.promptHistory("chat")).isEqualTo(legacy.promptHistory("chat"));
      assertThat(restarted.promptHistory("chat").attempts())
          .extracting(dev.mikoto2000.rei.context.CancelledTaskHistory.Attempt::runId)
          .containsExactly("run-0", "run-1", "run-2");
      assertThat(restarted.cancelledContext("chat")).isEqualTo(legacy.cancelledContext("chat"));
      assertThat(restarted.cancelledContext("other")).isEmpty();
      assertThat(restarted.read("chat").getFirst().request()).isEqualTo(body);
    }
  }

  @ParameterizedTest
  @EnumSource(ConversationTurnStore.Status.class)
  void lifecyclePoliciesUseOnlyMetadataEvenWhenConversationBodiesCannotBeRead(
      ConversationTurnStore.Status status) throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
      var sql = new SqliteConversationTurnStore(root);
      var owner = new AgentRunContext("attempt", "chat", root);
      sql.start(owner, "generated body ".repeat(6000), Instant.EPOCH);
      if (status != ConversationTurnStore.Status.RUNNING)
        sql.finish(owner, status, "generated answer");
      // Corrupt only this isolated fixture's body. Lifecycle projection must not deserialize it.
      new StorageDatabase(root).transaction(connection -> {
        try (var update = connection.prepareStatement("UPDATE turns SET record='not-json'")) {
          assertThat(update.executeUpdate()).isEqualTo(1);
        }
        return null;
      });
      var restarted = new SqliteConversationTurnStore(root);
      boolean archived =
          status == ConversationTurnStore.Status.FAILED
              || status == ConversationTurnStore.Status.CANCELLED;
      if (archived)
        assertThat(restarted.cancelledContext("chat"))
            .contains("archived", "CANCELLED", "FAILED", "getConversationHistory")
            .doesNotContain("generated body", "generated answer");
      else assertThat(restarted.cancelledContext("chat")).isEmpty();
      assertThat(restarted.promptHistory("chat").hides("attempt")).isEqualTo(archived);
      assertThat(restarted.promptHistory("other").runIds()).isEmpty();
      assertThat(restarted.cancelledContext("other")).isEmpty();
      assertThatThrownBy(() -> restarted.read("chat")).isInstanceOf(IllegalStateException.class);
    }
  }
}
