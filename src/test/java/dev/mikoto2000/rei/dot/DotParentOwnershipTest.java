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

/** 状態表示親所有権の結果と状態の契約を検証するテスト。 */
class DotParentOwnershipTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使うリポジトリ。 */
  DotRequestRepository repo;

  /** 検証に使う受付台帳。 */
  dev.mikoto2000.rei.conversation.ConversationAdmissionStore admissions;

  /** 検証に使う所有者。 */
  AgentRunContext owner;

  /** 検証に使う識別子。 */
  String id;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
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

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void close() {
    admissions.close();
  }

  /** 利用者の状態表示の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
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

  /** 利用者の状態表示で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
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

  /** 利用者の状態表示で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
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
