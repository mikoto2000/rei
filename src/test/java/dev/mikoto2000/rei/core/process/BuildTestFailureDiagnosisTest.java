package dev.mikoto2000.rei.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 構築検証失敗情報診断の結果と状態の契約を検証するテスト。 */
class BuildTestFailureDiagnosisTest {
  /** 検証に使う検証条件作業ルート。 */
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path fixtureRoot;

  /** 検証で使用する結果を、指定された条件に合わせて用意する。 */
  RunCommandResult result(String status, Integer exit, String out, String err, boolean timeout) {
    return new RunCommandResult(
        status, "foreground", "foreground", exit, out, err, null, null, timeout, null);
  }

  /** 外部プロセスの管理で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void extractsReportedTestsAndCausesWithStreamEvidence() {
    var value =
        result(
                "failed",
                1,
                "[ERROR] demo.WidgetTest.fails -- Time elapsed: 0.1 s <<< FAILURE!\n[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0",
                "java.lang.IllegalStateException: wrapper\nCaused by: java.io.IOException: disk unavailable",
                false)
            .diagnosis();
    assertEquals("FAILED", value.outcome());
    assertEquals("TEST_FAILURE", value.category());
    assertTrue(value.failedTests().contains("demo.WidgetTest.fails"));
    assertTrue(
        value.causes().stream()
            .anyMatch(e -> e.source().equals("stderr") && e.text().contains("disk unavailable")));
    assertFalse(value.nextActions().isEmpty());
  }

  /** 外部プロセスの管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void distinguishesCompilerDependencyAndUnknownExitWithoutInventingCause() {
    assertEquals(
        "COMPILATION_FAILURE",
        result("failed", 1, "[ERROR] /repo/App.java:[12,3] cannot find symbol", "", false)
            .diagnosis()
            .category());
    assertEquals(
        "DEPENDENCY_FAILURE",
        result("failed", 1, "[ERROR] Could not resolve dependencies for project demo", "", false)
            .diagnosis()
            .category());
    var unknown = result("failed", 7, "", "", false).diagnosis();
    assertEquals("UNKNOWN", unknown.category());
    assertTrue(unknown.evidence().isEmpty());
    assertTrue(unknown.causes().isEmpty());
  }

  /** 外部プロセスの管理で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void timeoutRunningAndKilledAreProcessFactsNotTestFailures() {
    assertEquals("TIMED_OUT", result("failed", -1, "", "", true).diagnosis().outcome());
    assertEquals("RUNNING", result("running", null, "", "", false).diagnosis().outcome());
    var killed =
        new BackgroundProcessSnapshot(
            "proc",
            1,
            BackgroundProcessStatus.KILLED,
            137,
            Instant.EPOCH,
            Instant.EPOCH,
            1,
            List.of(),
            List.of(),
            true,
            "killed");
    assertEquals("CANCELLED", killed.diagnosis().outcome());
    var missing =
        new BackgroundProcessSnapshot(
            "missing",
            -1,
            BackgroundProcessStatus.FAILED,
            null,
            null,
            null,
            0,
            List.of(),
            List.of(),
            false,
            "process not found");
    assertEquals("UNKNOWN", missing.diagnosis().outcome());
  }

  /** 外部プロセスの管理で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void redactsBeforeEvidenceClippingAndBoundsAnalysis() {
    var value =
        result(
                "failed",
                1,
                "Caused by: java.lang.Exception: token=secret-value",
                "password=\"secret with spaces\" java.lang.Exception: failed",
                false)
            .diagnosis();
    assertFalse(value.toString().contains("secret-value"));
    assertFalse(value.toString().contains("secret with spaces"));
    var oversized = result("failed", 1, "x".repeat(70000), "", false).diagnosis();
    assertTrue(oversized.partial());
    assertEquals("UNKNOWN", oversized.category());
    var many =
        result("failed", 1, "Caused by: java.lang.Exception: failed\n".repeat(100), "", false)
            .diagnosis();
    assertTrue(many.partial());
    assertTrue(many.evidence().size() <= 24);
    assertTrue(many.causes().size() <= 8);
  }

  /** 外部プロセスの管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void successfulOutputDoesNotTurnOrdinaryWordsIntoFailuresAndJsonIncludesDiagnosis() {
    var success =
        result(
            "completed",
            0,
            "failure recovery example\nTests run: 2, Failures: 0, Errors: 0, Skipped: 0",
            "",
            false);
    assertEquals("NONE", success.diagnosis().category());
    assertEquals("SUCCEEDED", success.diagnosis().outcome());
    String json =
        new org.springframework.ai.tool.execution.DefaultToolCallResultConverter()
            .convert(success, null);
    assertTrue(json.contains("\"diagnosis\""));
    assertTrue(json.contains("SUCCEEDED"));
  }

  /** 外部プロセスの管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void testClassSummaryIsNotAReportedTestNameAndNegativeTestOutputDoesNotOverrideExitCode() {
    var value =
        result(
                "failed",
                1,
                "[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE! -- in demo.AppTest",
                "",
                false)
            .diagnosis();
    assertEquals("TEST_FAILURE", value.category());
    assertTrue(value.failedTests().isEmpty());
    var negative =
        result("completed", 0, "java.lang.IllegalStateException: expected negative case", "", false)
            .diagnosis();
    assertEquals("SUCCEEDED", negative.outcome());
    assertEquals("EXCEPTION_REPORTED", negative.category());
    assertTrue(negative.warnings().stream().anyMatch(w -> w.contains("exit code 0")));
  }

  /** 診断根拠を返すことを確認する。 */
  @Test
  void aRealManagedProcessReturnsDiagnosticEvidenceWithoutStartingAnotherCommand()
      throws Exception {
    var manager =
        new BackgroundProcessManager(new dev.mikoto2000.rei.core.service.SystemShellService());
    try {
      var started =
          manager.spawnCommandLine(
              dev.mikoto2000.rei.testsupport.JavaFixtureCommand.command(
                  fixtureRoot, FailureFixture.class),
              fixtureRoot);
      var observed = manager.await(started.processId(), java.time.Duration.ofSeconds(10));
      assertEquals("FAILED", observed.diagnosis().outcome());
      assertEquals("TEST_FAILURE", observed.diagnosis().category());
      assertTrue(observed.diagnosis().failedTests().contains("demo.AppTest.fail"));
      assertTrue(observed.diagnosis().causes().stream().anyMatch(e -> e.source().equals("stderr")));
    } finally {
      manager.shutdown();
    }
  }

  /** 検証条件を再現するための失敗情報検証条件。 */
  public static class FailureFixture {
    /** 検証用に、外部プロセスの管理の起動要求を受け付け、実行を開始する。 */
    public static void main(String[] args) {
      System.out.println("[ERROR] demo.AppTest.fail -- Time elapsed: 0.1 s <<< FAILURE!");
      System.err.println("Caused by: java.lang.IllegalStateException: fixture failure");
      System.exit(1);
    }
  }
}
