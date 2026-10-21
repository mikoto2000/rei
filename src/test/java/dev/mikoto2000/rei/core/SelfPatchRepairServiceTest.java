package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Self変更差分修復サービスの結果と状態の契約を検証するテスト。 */
class SelfPatchRepairServiceTest {
  /** 共通の実行基盤で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void callerDeadlineIsSharedWithoutReplenishment() throws Exception {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
    var service =
        new SelfPatchRepairService(
            (r, q, d) -> {
              assertEquals(deadline, d);
              return round("VERIFIED_CHECKS", "v1");
            },
            (r, d) -> snapshot("v1"),
            (r, f, d) -> {
              fail("no repair");
              return null;
            });
    assertEquals("VERIFIED_CHECKS", service.verify(root, request(), deadline).status());
    var exhausted =
        new SelfPatchRepairService(
            (r, q, d) -> {
              fail("no exhausted command");
              return null;
            },
            (r, d) -> snapshot("v1"),
            (r, f, d) -> null);
    assertEquals("BLOCKED", exhausted.verify(root, request(), System.nanoTime() - 1).status());
  }

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** {@code round} の条件を再現するための補助情報を返す。 */
  SelfPatchReviewService.Result round(String status, String version) {
    return new SelfPatchReviewService.Result(
        root.toString(),
        status,
        version,
        version,
        List.of("A.java"),
        new SelfPatchReviewService.TestObservation(
            status.equals("INITIAL_TEST_FAILED") ? "failed" : "completed",
            status.equals("INITIAL_TEST_FAILED") ? 1 : 0,
            false,
            false,
            null),
        status.equals("FIX_REQUIRED")
            ? new SelfPatchReviewService.Review(
                true,
                List.of(new SelfPatchReviewService.Finding("A.java", 1, "WHITESPACE")),
                List.of())
            : null,
        null,
        List.of(),
        List.of());
  }

  /** 検証で使用する状態の記録を、指定された条件に合わせて用意する。 */
  SelfPatchReviewService.Snapshot snapshot(String version) {
    return new SelfPatchReviewService.Snapshot(
        version, List.of("A.java"), List.of(), true, List.of());
  }

  /** 検証で使用する要求を、指定された条件に合わせて用意する。 */
  SelfPatchRepairService.Request request() {
    return new SelfPatchRepairService.Request(
        "fixture", 10, List.of(new SelfPatchRepairService.Repair("fix", "hash")));
  }

  /** 元の失敗情報を保持することを確認する。 */
  @Test
  void fixesAReportedFailureThenRepeatsEntireReviewAndPreservesOriginalFailure() throws Exception {
    var steps = new ArrayList<String>();
    var version = new AtomicInteger();
    var deadline = new java.util.concurrent.atomic.AtomicLong();
    var service =
        new SelfPatchRepairService(
            (r, q, d) -> {
              if (deadline.get() == 0) deadline.set(d);
              else assertEquals(deadline.get(), d);
              steps.add("cycle");
              return round(
                  version.get() == 0 ? "INITIAL_TEST_FAILED" : "VERIFIED_CHECKS",
                  "v" + version.get());
            },
            (r, d) -> {
              assertEquals(deadline.get(), d);
              steps.add("snapshot");
              return snapshot("v" + version.get());
            },
            (r, fix, d) -> {
              assertEquals(deadline.get(), d);
              steps.add("fix");
              version.incrementAndGet();
              return new SelfPatchRepairService.Receipt(fix.id(), "APPLIED", "new", "new");
            });
    var result = service.verify(root, request());
    assertEquals("VERIFIED_CHECKS", result.status());
    assertEquals(List.of("cycle", "snapshot", "fix", "snapshot", "cycle"), steps);
    assertEquals(2, result.rounds().size());
    assertEquals(1, result.rounds().getFirst().initialTest().exitCode());
    assertEquals(1, result.repairs().size());
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void changedPatchIncompleteReviewOrUncertainRepairNeverStartsAnotherFix() throws Exception {
    for (var status : List.of("PATCH_CHANGED", "BLOCKED", "REVIEW_INCOMPLETE", "NO_PATCH")) {
      var service =
          new SelfPatchRepairService(
              (r, q, d) -> round(status, "v1"),
              (r, d) -> {
                fail("no capture");
                return snapshot("v1");
              },
              (r, f, d) -> {
                fail("no fix");
                return null;
              });
      assertEquals(status, service.verify(root, request()).status());
    }
    var changed =
        new SelfPatchRepairService(
            (r, q, d) -> round("FIX_REQUIRED", "v1"),
            (r, d) -> snapshot("other"),
            (r, f, d) -> {
              fail("no changed patch write");
              return null;
            });
    assertEquals("PATCH_CHANGED", changed.verify(root, request()).status());
    var uncertain =
        new SelfPatchRepairService(
            (r, q, d) -> round("FIX_REQUIRED", "v1"),
            (r, d) -> snapshot("v1"),
            (r, f, d) ->
                new SelfPatchRepairService.Receipt(f.id(), "FAILED_UNCERTAIN", "new", "partial"));
    assertEquals("REPAIR_REJECTED", uncertain.verify(root, request()).status());
  }

  /** 共通の実行基盤で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void noProgressAndRepairLimitStopRatherThanLoopOrClaimSuccess() throws Exception {
    var count = new AtomicInteger();
    var unchanged =
        new SelfPatchRepairService(
            (r, q, d) -> round("FIX_REQUIRED", "v1"),
            (r, d) -> snapshot("v1"),
            (r, f, d) -> new SelfPatchRepairService.Receipt(f.id(), "APPLIED", "new", "new"));
    assertEquals("REPAIR_NO_PROGRESS", unchanged.verify(root, request()).status());
    var limited =
        new SelfPatchRepairService(
            (r, q, d) -> round("FIX_REQUIRED", "v" + count.get()),
            (r, d) -> snapshot("v" + count.get()),
            (r, f, d) -> {
              count.incrementAndGet();
              return new SelfPatchRepairService.Receipt(f.id(), "APPLIED", "new", "new");
            });
    assertEquals("REPAIR_LIMIT_REACHED", limited.verify(root, request()).status());
    assertEquals(1, count.get());
  }

  /** 共通の実行基盤の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void invalidPlanAndCancellationStopBeforeAnotherStage() throws Exception {
    var service =
        new SelfPatchRepairService(
            (r, q, d) -> {
              throw new java.util.concurrent.CancellationException();
            },
            (r, d) -> snapshot("v1"),
            (r, f, d) -> null);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.verify(root, new SelfPatchRepairService.Request("fixture", 10, List.of())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.verify(
                root,
                new SelfPatchRepairService.Request(
                    "fixture",
                    10,
                    List.of(
                        new SelfPatchRepairService.Repair("same", "hash"),
                        new SelfPatchRepairService.Repair("same", "hash")))));
    assertThrows(
        java.util.concurrent.CancellationException.class, () -> service.verify(root, request()));
  }

  /** 同一上限内に収めた計画を保持することを確認する。 */
  @Test
  void maximumThreeRepairsAndFourWholeCyclesRetainTheSameBoundedPlan() throws Exception {
    var fixes = new AtomicInteger();
    var cycles = new AtomicInteger();
    var service =
        new SelfPatchRepairService(
            (r, q, d) -> {
              cycles.incrementAndGet();
              return round("FIX_REQUIRED", "v" + fixes.get());
            },
            (r, d) -> snapshot("v" + fixes.get()),
            (r, f, d) -> {
              fixes.incrementAndGet();
              return new SelfPatchRepairService.Receipt(f.id(), "APPLIED", "new", "new");
            });
    var plan = new ArrayList<SelfPatchRepairService.Repair>();
    for (int i = 0; i < 3; i++) plan.add(new SelfPatchRepairService.Repair("fix-" + i, "hash"));
    assertEquals(
        "REPAIR_LIMIT_REACHED",
        service.verify(root, new SelfPatchRepairService.Request("fixture", 10, plan)).status());
    assertEquals(3, fixes.get());
    assertEquals(4, cycles.get());
    plan.add(new SelfPatchRepairService.Repair("fourth", "hash"));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.verify(root, new SelfPatchRepairService.Request("fixture", 10, plan)));
    assertEquals(3, fixes.get());
    assertEquals(4, cycles.get());
  }

  /** 共通の実行基盤で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void opaqueFailedTestsAndUnknownApplyReceiptsDoNotContinue() throws Exception {
    for (boolean timeout : List.of(true, false)) {
      var failed =
          new SelfPatchReviewService.Result(
              root.toString(),
              "INITIAL_TEST_FAILED",
              "v1",
              "v1",
              List.of("A.java"),
              new SelfPatchReviewService.TestObservation("failed", 1, timeout, !timeout, null),
              null,
              null,
              List.of(),
              List.of());
      var service =
          new SelfPatchRepairService(
              (r, q, d) -> failed,
              (r, d) -> {
                fail("no capture for incomplete test");
                return snapshot("v1");
              },
              (r, f, d) -> {
                fail("no opaque fix");
                return null;
              });
      assertEquals("INITIAL_TEST_FAILED", service.verify(root, request()).status());
    }
    for (boolean throwsError : List.of(true, false)) {
      var service =
          new SelfPatchRepairService(
              (r, q, d) -> round("FIX_REQUIRED", "v1"),
              (r, d) -> snapshot("v1"),
              (r, f, d) -> {
                if (throwsError) throw new java.io.IOException("uncertain write");
                return null;
              });
      var result = service.verify(root, request());
      assertEquals(throwsError ? "REPAIR_FAILED" : "REPAIR_REJECTED", result.status());
      assertEquals(1, result.rounds().size());
      assertEquals("UNKNOWN", result.repairs().getFirst().status());
    }
  }
}
