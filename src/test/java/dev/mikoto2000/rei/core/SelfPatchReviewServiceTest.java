package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.mikoto2000.rei.core.process.BuildTestFailureDiagnosis;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Self変更差分レビューサービスの結果と状態の契約を検証するテスト。 */
class SelfPatchReviewServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証で使用する状態の記録を、指定された条件に合わせて用意する。 */
  SelfPatchReviewService.Snapshot snapshot(String version) {
    return new SelfPatchReviewService.Snapshot(
        version, List.of("A.java"), List.of(), true, List.of());
  }

  /** {@code test} の条件を再現するための補助情報を返す。 */
  SelfPatchReviewService.TestObservation test(boolean success) {
    return new SelfPatchReviewService.TestObservation(
        success ? "completed" : "failed",
        success ? 0 : 1,
        false,
        false,
        BuildTestFailureDiagnosis.command(
            success ? "completed" : "failed", success ? 0 : 1, false, "", "", null));
  }

  /** {@code review} の条件を再現するための補助情報を返す。 */
  SelfPatchReviewService.Review review() {
    return new SelfPatchReviewService.Review(true, List.of(), List.of());
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void verifiesInitialTestReviewAndFinalTestAgainstTheSamePatchInOrder() throws Exception {
    var steps = new ArrayList<String>();
    var service =
        new SelfPatchReviewService(
            (r, d) -> {
              assertEquals(root.toRealPath(), r);
              steps.add("snapshot");
              return snapshot("v1");
            },
            (r, s, d) -> {
              steps.add("review");
              return review();
            },
            (r, c, t) -> {
              assertEquals("fixture test", c);
              assertTrue(t.toSeconds() <= 30);
              steps.add("test");
              return test(true);
            });
    var result = service.verify(root, new SelfPatchReviewService.Request("fixture test", 30));
    assertEquals("VERIFIED_CHECKS", result.status());
    assertEquals(
        List.of("snapshot", "test", "snapshot", "review", "snapshot", "test", "snapshot"), steps);
    assertEquals("v1", result.patchVersion());
    assertNotNull(result.finalTest());
  }

  /** 共通の実行基盤で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failedInitialTestNeverReviewsOrRunsFinalTest() throws Exception {
    var calls = new AtomicInteger();
    var service =
        new SelfPatchReviewService(
            (r, d) -> snapshot("v1"),
            (r, s, d) -> {
              fail("review must not run");
              return review();
            },
            (r, c, t) -> {
              calls.incrementAndGet();
              return test(false);
            });
    assertEquals(
        "INITIAL_TEST_FAILED",
        service.verify(root, new SelfPatchReviewService.Request("fixture", null)).status());
    assertEquals(1, calls.get());
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void reviewFindingsReturnToTheExistingFixLoopWithoutClaimingVerification() throws Exception {
    var calls = new AtomicInteger();
    var service =
        new SelfPatchReviewService(
            (r, d) -> snapshot("v1"),
            (r, s, d) ->
                new SelfPatchReviewService.Review(
                    true,
                    List.of(new SelfPatchReviewService.Finding("A.java", 3, "CONFLICT_MARKER")),
                    List.of()),
            (r, c, t) -> {
              calls.incrementAndGet();
              return test(true);
            });
    var result = service.verify(root, new SelfPatchReviewService.Request("fixture", 10));
    assertEquals("FIX_REQUIRED", result.status());
    assertEquals(1, calls.get());
    assertNull(result.finalTest());
  }

  /** 共通の実行基盤で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void changesDuringInitialOrFinalTestInvalidateTheCycle() throws Exception {
    for (int changeAt : List.of(2, 4)) {
      var snapshots = new AtomicInteger();
      var calls = new AtomicInteger();
      var service =
          new SelfPatchReviewService(
              (r, d) -> snapshot(snapshots.incrementAndGet() >= changeAt ? "changed" : "v1"),
              (r, s, d) -> review(),
              (r, c, t) -> {
                calls.incrementAndGet();
                return test(true);
              });
      assertEquals(
          "PATCH_CHANGED",
          service.verify(root, new SelfPatchReviewService.Request("fixture", 10)).status());
      assertEquals(changeAt == 2 ? 1 : 2, calls.get());
    }
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void incompleteReviewOrTruncatedTestLogsCannotVerify() throws Exception {
    var incomplete =
        new SelfPatchReviewService(
            (r, d) -> snapshot("v1"),
            (r, s, d) -> new SelfPatchReviewService.Review(false, List.of(), List.of("incomplete")),
            (r, c, t) -> test(true));
    assertEquals(
        "REVIEW_INCOMPLETE",
        incomplete.verify(root, new SelfPatchReviewService.Request("fixture", 10)).status());
    var truncated =
        new SelfPatchReviewService(
            (r, d) -> snapshot("v1"),
            (r, s, d) -> review(),
            (r, c, t) ->
                new SelfPatchReviewService.TestObservation(
                    "completed", 0, false, true, test(true).diagnosis()));
    assertEquals(
        "INITIAL_TEST_FAILED",
        truncated.verify(root, new SelfPatchReviewService.Request("fixture", 10)).status());
  }

  /** 共通の実行基盤で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failedFinalTestIsPreservedAndDoesNotVerify() throws Exception {
    var calls = new AtomicInteger();
    var service =
        new SelfPatchReviewService(
            (r, d) -> snapshot("v1"),
            (r, s, d) -> review(),
            (r, c, t) -> test(calls.incrementAndGet() == 1));
    var result = service.verify(root, new SelfPatchReviewService.Request("fixture", 10));
    assertEquals("FINAL_TEST_FAILED", result.status());
    assertEquals(1, result.finalTest().exitCode());
  }

  /** 共通の実行基盤の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void invalidArgumentsAndCancellationStopBeforeAnotherStage() throws Exception {
    var service =
        new SelfPatchReviewService(
            (r, d) -> snapshot("v1"),
            (r, s, d) -> review(),
            (r, c, t) -> {
              Thread.currentThread().interrupt();
              return test(true);
            });
    assertThrows(
        IllegalArgumentException.class,
        () -> service.verify(root, new SelfPatchReviewService.Request("", 10)));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.verify(root, new SelfPatchReviewService.Request("fixture", 61)));
    try {
      assertThrows(
          CancellationException.class,
          () -> service.verify(root, new SelfPatchReviewService.Request("fixture", 10)));
    } finally {
      Thread.interrupted();
    }
  }
}
