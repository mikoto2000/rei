package dev.mikoto2000.rei.dot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.application.session.SessionMetadata;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotParentOwnershipTest {
  @TempDir Path root;
  DotRequestRepository repo;
  dev.mikoto2000.rei.conversation.ConversationAdmissionStore admissions;
  AgentRunContext owner;
  String id;

  @BeforeEach
  void setup() throws Exception {
    try (var gate = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    var clock = Clock.systemUTC();
    admissions = new dev.mikoto2000.rei.conversation.ConversationAdmissionStore(root, clock);
    owner =
        new AgentRunContext(
            UUID.randomUUID().toString(), "session", root, UUID.randomUUID().toString());
    admissions.accept(
        new SessionMetadata(
            "session", owner.projectId(), "session", clock.instant(), clock.instant()),
        owner,
        null,
        null);
    admissions.transition(owner.runId(), RunStatus.RUNNING);
    admissions.transition(owner.runId(), RunStatus.WAITING);
    repo = new DotRequestRepository(root, clock);
    id =
        (String)
            repo.create(
                    owner.projectId(),
                    "session",
                    owner.runId(),
                    root.toString(),
                    DotPayload.publication(Map.of("instruction", "research")),
                    Duration.ofHours(1))
                .get("request_id");
    repo.bind(id, owner.projectId(), "task", "dep", Map.of());
    repo.publish(owner.projectId(), id);
  }

  @AfterEach
  void close() {
    admissions.close();
  }

  @Test
  void sameTransactionUsesCanonicalRunCancellationAndHistoricalReceiptOnly() {
    var claim = repo.claim(owner.projectId(), id, "claim");
    admissions.transition(owner.runId(), RunStatus.CANCELLED);
    assertThrows(
        DotException.class,
        () ->
            repo.submit(
                owner.projectId(), id, "claim", "submission", Map.of("summary", "report"), "hash"));
    assertEquals(claim, repo.claim(owner.projectId(), id, "claim"));
    assertEquals("CLAIMED", repo.get(owner.projectId(), id).get("status"));
  }

  @Test
  void wrongStoredSessionAndLostRunCannotBeClaimed() {
    new dev.mikoto2000.rei.storage.StorageDatabase(root)
        .transaction(
            c -> {
              try (var q =
                  c.prepareStatement(
                      "UPDATE conversation_admissions SET"
                          + " context=json_set(context,'$.sessionId','different') WHERE"
                          + " run_id=?")) {
                q.setString(1, owner.runId());
                q.executeUpdate();
              }
              return null;
            });
    assertThrows(DotException.class, () -> repo.claim(owner.projectId(), id, "claim"));
  }

  @Test
  void unpublishedParentAdmissionIsRecoveredAsUnknownWithoutRequeue() {
    var other =
        new AgentRunContext(UUID.randomUUID().toString(), "session", root, owner.projectId());
    String pending =
        (String)
            repo.create(
                    owner.projectId(),
                    "session",
                    other.runId(),
                    root.toString(),
                    DotPayload.publication(Map.of("instruction", "other")),
                    Duration.ofHours(1))
                .get("request_id");
    assertFalse(repo.published(owner.projectId(), pending));
    repo.recoverAdmissions();
    assertEquals("UNKNOWN", repo.get(owner.projectId(), pending).get("status"));
    assertFalse(repo.published(owner.projectId(), pending));
  }
}
