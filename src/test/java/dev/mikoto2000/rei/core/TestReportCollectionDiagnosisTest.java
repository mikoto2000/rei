package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 検証レポート集約対象診断の結果と状態の契約を検証するテスト。 */
class TestReportCollectionDiagnosisTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path outside;

  /** {@code report} の条件を再現するため、検証用の操作や状態を扱う。 */
  private void report(String path, boolean failure) throws Exception {
    var file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        "<testsuite tests='1' failures='"
            + (failure ? 1 : 0)
            + "' errors='0' skipped='0'><testcase name='sample'>"
            + (failure
                ? "<failure message='token=secret-value'>password=private-value</failure>"
                : "")
            + "</testcase></testsuite>");
  }

  /** ごとのファイル識別情報を保持することを確認する。 */
  @Test
  void discoversStandardMultiModuleReportsAndPreservesPerFileIdentity() throws Exception {
    report("target/surefire-reports/TEST-one.xml", false);
    report("module/target/failsafe-reports/TEST-two.xml", true);
    report("other/build/test-results/test/TEST-three.xml", false);
    report("src/TEST-not-a-report-location.xml", true);
    report("node_modules/module/target/surefire-reports/TEST-excluded.xml", true);
    var result = new TestReportCollectionDiagnosisService().read(root, null);
    assertEquals(3, result.reports().size());
    assertEquals(3, result.observed().tests());
    assertEquals(1, result.observed().failures());
    assertEquals(3, result.reported().tests());
    assertFalse(result.partial());
    assertEquals(1, result.failedTests().size());
    assertTrue(result.failedTests().getFirst().path().contains("failsafe-reports"));
    for (var summary : result.reports()) {
      assertTrue(summary.sha256().matches("[a-f0-9]{64}"));
      assertNotNull(summary.modifiedAt());
    }
    assertFalse(result.toString().contains("secret-value"));
    assertFalse(result.toString().contains("private-value"));
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("current process")));
  }

  /** 共通の実行基盤で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void explicitDirectoryDoesNotExpandToOtherReportsAndInvalidFilesArePartial() throws Exception {
    report("custom/TEST-one.xml", true);
    report("target/surefire-reports/TEST-other.xml", false);
    Files.writeString(root.resolve("custom/TEST-broken.xml"), "<!DOCTYPE testsuite><testsuite/>");
    var result = new TestReportCollectionDiagnosisService().read(root, "custom");
    assertEquals(1, result.reports().size());
    assertEquals(1, result.observed().tests());
    assertEquals(1, result.unavailable().size());
    assertTrue(result.partial());
    assertEquals("report_unavailable_or_invalid", result.unavailable().getFirst().reason());
    assertFalse(result.toString().contains("DOCTYPE"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TestReportCollectionDiagnosisService().read(root, "../elsewhere"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TestReportCollectionDiagnosisService().read(root, ".aws"));
  }

  /** 許容範囲について、件数を伝えることを確認する。 */
  @Test
  void boundsReportCountAndGlobalEvidenceWithoutHidingIncompleteObservation() throws Exception {
    for (int i = 0; i < 33; i++) report("target/surefire-reports/TEST-%02d.xml".formatted(i), true);
    var result = new TestReportCollectionDiagnosisService().read(root, null);
    assertEquals(32, result.reports().size());
    assertEquals(32, result.discoveredPaths().size());
    assertEquals(32, result.observed().failures());
    assertEquals(24, result.failedTests().size());
    assertTrue(result.partial());
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("32 files")));
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("24 issues")));
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void missingCountsDoNotInventReportedTotalsAndOverlappingFilesRemainSeparate() throws Exception {
    report("custom/TEST-one.xml", false);
    report("custom/TEST-two.xml", false);
    var service = new TestReportCollectionDiagnosisService();
    var result = service.read(root, "custom");
    assertEquals(2, result.observed().tests());
    assertEquals(2, result.reports().size());
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("overlapping")));
    Files.writeString(
        root.resolve("custom/TEST-two.xml"), "<testsuite><testcase name='sample'/></testsuite>");
    result = service.read(root, "custom");
    assertNull(result.reported());
    assertEquals(2, result.observed().tests());
    assertTrue(result.partial());
    Files.createDirectories(root.resolve("empty"));
    result = service.read(root, "empty");
    assertNull(result.reported());
    assertEquals(0, result.observed().tests());
    assertTrue(result.partial());
  }

  /** 共通の実行基盤で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void reportedTotalsUseLongsAndFileBytesAndDepthAreBounded() throws Exception {
    for (int i = 0; i < 3; i++) {
      var file = root.resolve("custom/TEST-" + i + ".xml");
      Files.createDirectories(file.getParent());
      Files.writeString(file, "<testsuite tests='999999999' failures='0' errors='0' skipped='0'/>");
    }
    var service = new TestReportCollectionDiagnosisService();
    var result = service.read(root, "custom");
    assertEquals(2999999997L, result.reported().tests());
    assertTrue(result.partial());
    Files.writeString(root.resolve("custom/TEST-large.xml"), "x".repeat(1048577));
    result = service.read(root, "custom");
    assertEquals(1, result.unavailable().size());
    assertTrue(result.partial());
    report("a/b/c/d/e/f/g/target/surefire-reports/TEST-deep.xml", false);
    result = service.read(root, null);
    assertTrue(result.partial());
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("depth")));
  }

  /** 共通の実行基盤の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void directoryAndEntryLimitsArePartialAndCancelledCallsPropagate() throws Exception {
    for (int i = 0; i < 257; i++) Files.createDirectory(root.resolve("module-%03d".formatted(i)));
    var service = new TestReportCollectionDiagnosisService();
    var result = service.read(root, null);
    assertTrue(result.partial());
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("256 folders")));
    Files.createDirectory(root.resolve("many"));
    for (int i = 0; i < 4097; i++) Files.createFile(root.resolve("many/file-" + i));
    result = service.read(root, "many");
    assertTrue(result.partial());
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("4096 entries")));
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          java.util.concurrent.CancellationException.class, () -> service.read(root, null));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  /** 実行プロジェクトを利用することを確認する。 */
  @Test
  void realToolUsesRunProjectAndRequiresExplicitReadOnlySubAgentAccess() throws Exception {
    report("target/surefire-reports/TEST-one.xml", true);
    var callback =
        java.util.Arrays.stream(
                org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                    .toolObjects(new Tools())
                    .build()
                    .getToolCallbacks())
            .filter(c -> c.getToolDefinition().name().equals("diagnoseTestReports"))
            .findFirst()
            .orElseThrow();
    try (var scope =
        dev.mikoto2000.rei.core.chat.AgentRunScope.open(
            new dev.mikoto2000.rei.core.chat.AgentRunContext("run", "session", root))) {
      var response = callback.call("{}");
      assertTrue(response.contains("STANDARD_DISCOVERY"));
      assertTrue(response.contains("FAILURE"));
      assertEquals(
          1, new Tools().diagnoseTestReports("target/surefire-reports").observed().tests());
    }
    var policy =
        new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(
            new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true, null, null, null));
    assertEquals(
        java.util.Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.READ),
        policy.capabilities("diagnoseTestReports"));
    var child =
        new dev.mikoto2000.rei.subagent.SubAgentToolPolicy(java.util.Set.of("diagnoseTestReports"));
    assertTrue(child.effectiveTools(java.util.List.of()).isEmpty());
    child.validate(java.util.List.of("diagnoseTestReports"));
    assertEquals(
        java.util.List.of("diagnoseTestReports"),
        child.effectiveTools(java.util.List.of("diagnoseTestReports")));
  }

  /** 共通の実行基盤で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  @org.junit.jupiter.api.Tag("integration")
  void linkedOutsideDirectoryIsRejectedWithoutReadingItsReport() throws Exception {
    Files.writeString(
        outside.resolve("TEST-private.xml"),
        "<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase name='outside'/></testsuite>");
    var link = root.resolve("linked");
    if (System.getProperty("os.name").startsWith("Windows")) {
      var process =
          new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), outside.toString())
              .redirectErrorStream(true)
              .start();
      assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(0, process.exitValue());
    } else Files.createSymbolicLink(link, outside);
    try {
      var service = new TestReportCollectionDiagnosisService();
      assertThrows(java.io.IOException.class, () -> service.read(root, "linked"));
      var result = service.read(root, null);
      assertTrue(result.partial());
      assertTrue(result.reports().isEmpty());
      assertFalse(result.toString().contains("TEST-private"));
      assertThrows(IllegalArgumentException.class, () -> service.read(root, outside.toString()));
    } finally {
      Files.deleteIfExists(link);
    }
  }
}
