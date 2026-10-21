package dev.mikoto2000.rei.dot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 状態表示レビューの結果と状態の契約を検証するテスト。 */
class DotReviewTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使うリポジトリ。 */
  DotRequestRepository repo;

  /** 検証に使う識別子。 */
  String id;

  /** 検証に使うプロジェクト。 */
  String project = "p";

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() throws Exception {
    try (var gate = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    repo = new DotRequestRepository(root, Clock.systemUTC());
    id =
        (String)
            repo.create(
                    project,
                    "s",
                    null,
                    root.toString(),
                    DotPayload.publication(
                        Map.of(
                            "instruction",
                            "research",
                            "acceptance_criteria",
                            List.of("source", "compare"))),
                    Duration.ofHours(1))
                .get("request_id");
    repo.claim(project, id, "claim");
    repo.submit(
        project,
        id,
        "claim",
        "submit",
        Map.of("summary", "report", "outcome", "COMPLETED"),
        "hash");
  }

  /** 利用者の状態表示で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void receiptRemainsPendingReviewAfterHumanVerificationAndRestart() {
    assertEquals("RESULT_RECEIVED", repo.get(project, id).get("status"));
    assertThrows(
        DotException.class,
        () -> repo.review(project, id, "VERIFIED", List.of(0), "human checked"));
    repo.review(project, id, "VERIFIED", List.of(0, 1), "human checked");
    var restarted = new DotRequestRepository(root, Clock.systemUTC());
    assertEquals("VERIFIED", restarted.get(project, id).get("status"));
    assertEquals(
        "PENDING_REVIEW",
        repo.submit(project, id, "claim", "submit", Map.of(), "hash").get("validation_status"));
  }

  /** 利用者の状態表示で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void manualIntentIsUniqueAndCrashNeverCreatesAnotherRun() {
    assertThrows(DotException.class, () -> repo.requestResume(project, id, 1));
    repo.review(project, id, "VERIFIED", List.of(0, 1), "checked");
    assertTrue(repo.requestResume(project, id, 7));
    assertFalse(repo.requestResume(project, id, 7));
    repo.attachSchedule(project, id, "timer-one");
    assertTrue(repo.startResume(project, id, "timer-one", "run-one"));
    assertFalse(repo.startResume(project, id, "timer-one", "run-two"));
    repo.recoverResumeIntents();
    assertEquals("UNKNOWN", repo.intent(project, id).get("status"));
    assertEquals("run-one", repo.intent(project, id).get("run"));
  }

  /** 利用者の状態表示の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void rejectedOrCancelledResultCannotBeAdopted() {
    repo.cancel(project, id);
    assertThrows(
        DotException.class, () -> repo.review(project, id, "VERIFIED", List.of(0, 1), "checked"));
    assertThrows(DotException.class, () -> repo.requestResume(project, id, 1));
  }
}
