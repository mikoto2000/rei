package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 診断済み修復サービスの結果と状態の契約を検証するテスト。 */
class DiagnosedRepairServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使うプロジェクト。 */
  final String project = UUID.randomUUID().toString();

  /** 検証に使う形式のバージョン。 */
  final AtomicInteger version = new AtomicInteger();

  /** {@code owner} の条件を再現するための補助情報を返す。 */
  AgentRunContext owner() {
    return new AgentRunContext("run", "session", root, project);
  }

  /** 検証に使う共有取得元。 */
  org.springframework.jdbc.datasource.DriverManagerDataSource sharedSource;

  /** 検証で使用する取得元を、指定された条件に合わせて用意する。 */
  org.springframework.jdbc.datasource.DriverManagerDataSource source() {
    if (sharedSource == null)
      sharedSource =
          new org.springframework.jdbc.datasource.DriverManagerDataSource(
              "jdbc:sqlite:" + root.resolve("repairs.db"));
    return sharedSource;
  }

  /** {@code changes} の条件を再現するための補助情報を返す。 */
  TextChangeSetService changes() {
    return new TextChangeSetService(new TextChangeSetRepository(source()));
  }

  /** 検証で使用する状態の記録を、指定された条件に合わせて用意する。 */
  SelfPatchReviewService.Snapshot snapshot() {
    return new SelfPatchReviewService.Snapshot(
        "v" + version.get(), List.of("A.java"), List.of(), true, List.of());
  }

  /** {@code round} の条件を再現するための補助情報を返す。 */
  SelfPatchReviewService.Result round() {
    return new SelfPatchReviewService.Result(
        root.toString(),
        version.get() == 0 ? "INITIAL_TEST_FAILED" : "VERIFIED_CHECKS",
        "v" + version.get(),
        "v" + version.get(),
        List.of("A.java"),
        new SelfPatchReviewService.TestObservation(
            version.get() == 0 ? "failed" : "completed",
            version.get() == 0 ? 1 : 0,
            false,
            false,
            null),
        null,
        null,
        List.of(),
        List.of());
  }

  /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
  DiagnosedRepairService service(boolean enabled) {
    return new DiagnosedRepairService(
        source(),
        changes(),
        Clock.systemUTC(),
        enabled,
        (r, d) -> snapshot(),
        (r, q, d) -> round());
  }

  /** {@code fixture} の条件を再現するため、検証用の操作や状態を扱う。 */
  void fixture() throws Exception {
    Files.writeString(root.resolve("A.java"), "old");
    report(true);
  }

  /** {@code report} の条件を再現するため、検証用の操作や状態を扱う。 */
  void report(boolean failed) throws Exception {
    Files.createDirectories(root.resolve("target"));
    Files.writeString(
        root.resolve("target/TEST.xml"),
        "<testsuite tests=\"1\" failures=\""
            + (failed ? 1 : 0)
            + "\" errors=\"0\" skipped=\"0\"><testcase classname=\"ATest\" name=\"value\">"
            + (failed ? "<failure message=\"expected 1\">assertion</failure>" : "")
            + "</testcase></testsuite>");
  }

  /** 検証で使用する要求を、指定された条件に合わせて用意する。 */
  DiagnosedRepairService.Request request() {
    return new DiagnosedRepairService.Request(
        "target/TEST.xml",
        new TextChangeSetService.Request("A.java", "old", "new"),
        "fixture-test",
        10);
  }

  /** 共通の実行基盤の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void persistsDiagnosisProposalAndRequiresExactHumanApprovalBeforeExistingRepairCycle()
      throws Exception {
    fixture();
    var service = service(true);
    var proposal = service.propose(owner(), request());
    assertEquals("PROPOSED", proposal.status());
    assertEquals("old", Files.readString(root.resolve("A.java")));
    assertEquals(64, proposal.receiptSha256().length());
    assertEquals("ATest#value", proposal.diagnosis().failedTests().getFirst().test());
    var restarted = service(true);
    assertEquals(
        proposal.receiptSha256(), restarted.inspect(owner(), proposal.id()).receiptSha256());
    assertThrows(
        IllegalStateException.class,
        () ->
            changes()
                .apply(
                    new dev.mikoto2000.rei.core.project.ProjectContext(project, "", root),
                    proposal.id(),
                    proposal.changeSet().proposalSha256(),
                    (p, o, n) -> fail("ordinary Apply cannot bypass diagnosed approval")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            restarted.apply(
                owner(),
                proposal.id(),
                proposal.receiptSha256(),
                "please fix",
                (p, o, n) -> fail("no unapproved write")));
    var result =
        restarted.apply(
            owner(),
            proposal.id(),
            proposal.receiptSha256(),
            "/repair apply " + proposal.id() + " " + proposal.receiptSha256(),
            (p, o, n) -> {
              Files.writeString(p, n);
              version.incrementAndGet();
            });
    assertEquals("VERIFIED_CHECKS", result.status());
    assertEquals(2, result.verification().rounds().size());
    assertEquals(1, result.verification().rounds().getFirst().initialTest().exitCode());
    assertEquals("new", Files.readString(root.resolve("A.java")));
    assertEquals(
        "VERIFIED_CHECKS",
        service(true)
            .apply(
                owner(),
                proposal.id(),
                proposal.receiptSha256(),
                "/repair apply " + proposal.id() + " " + proposal.receiptSha256(),
                (p, o, n) -> fail("no replay"))
            .status());
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void disabledForeignStaleEvidenceAndChangedPatchCannotApply() throws Exception {
    fixture();
    assertThrows(IllegalStateException.class, () -> service(false).propose(owner(), request()));
    var service = service(true);
    var proposal = service.propose(owner(), request());
    assertThrows(
        IllegalArgumentException.class,
        () -> service.inspect(new AgentRunContext("other", "other", root, project), proposal.id()));
    report(false);
    assertThrows(
        IllegalStateException.class,
        () ->
            service.apply(
                owner(),
                proposal.id(),
                proposal.receiptSha256(),
                "/repair apply " + proposal.id() + " " + proposal.receiptSha256(),
                (p, o, n) -> fail("no stale diagnosis write")));
    report(true);
    version.incrementAndGet();
    assertThrows(
        IllegalStateException.class,
        () ->
            service.apply(
                owner(),
                proposal.id(),
                proposal.receiptSha256(),
                "/repair apply " + proposal.id() + " " + proposal.receiptSha256(),
                (p, o, n) -> fail("no changed patch write")));
  }

  /** 共通の実行基盤で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void uncertainWriteIsDurablyNotReplayableAndSuccessfulReportCannotProposeRepair()
      throws Exception {
    fixture();
    report(false);
    assertThrows(IllegalArgumentException.class, () -> service(true).propose(owner(), request()));
    report(true);
    var proposal = service(true).propose(owner(), request());
    var result =
        service(true)
            .apply(
                owner(),
                proposal.id(),
                proposal.receiptSha256(),
                "/repair apply " + proposal.id() + " " + proposal.receiptSha256(),
                (p, o, n) -> {
                  Files.writeString(p, "partial");
                  throw new java.io.IOException("uncertain");
                });
    assertEquals("REPAIR_FAILED", result.status());
    assertEquals("REPAIR_FAILED", service(true).inspect(owner(), proposal.id()).status());
    assertThrows(
        IllegalStateException.class,
        () ->
            service(true)
                .apply(
                    owner(),
                    proposal.id(),
                    proposal.receiptSha256(),
                    "/repair apply " + proposal.id() + " " + proposal.receiptSha256(),
                    (p, o, n) -> fail("no uncertain replay")));
  }

  /** ツールについて、信頼済み利用者要求を利用することを確認する。 */
  @Test
  void toolUsesTrustedUserRequestAndPreservesPolicyAndHumanShellEntry() throws Exception {
    fixture();
    var tools = new Tools();
    tools.setTextChangeSets(changes());
    tools.setDiagnosedRepairs(service(true));
    try (var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(owner())) {
      var proposal = tools.proposeDiagnosedRepair(request());
      assertEquals(proposal.id(), tools.inspectDiagnosedRepair(proposal.id()).id());
      var run =
          org.mockito.Mockito.mock(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.class);
      org.mockito.Mockito.when(run.runContext()).thenReturn(owner());
      org.mockito.Mockito.when(run.userRequest()).thenReturn("repair it");
      var context =
          new org.springframework.ai.chat.model.ToolContext(
              Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY, run));
      assertThrows(
          IllegalArgumentException.class,
          () -> tools.applyDiagnosedRepair(proposal.id(), proposal.receiptSha256(), context));
      assertEquals("old", Files.readString(root.resolve("A.java")));
      var callbacks =
          Arrays.stream(
                  org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                      .toolObjects(tools)
                      .build()
                      .getToolCallbacks())
              .map(value -> value.getToolDefinition().name())
              .toList();
      assertTrue(
          callbacks.containsAll(
              List.of("proposeDiagnosedRepair", "inspectDiagnosedRepair", "applyDiagnosedRepair")));
    }
    var policy =
        new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(
            new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true, null, null, null));
    assertEquals(
        Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.READ),
        policy.capabilities("inspectDiagnosedRepair"));
    assertEquals(
        Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.LOCAL_WRITE),
        policy.capabilities("proposeDiagnosedRepair"));
    assertTrue(
        policy
            .capabilities("applyDiagnosedRepair")
            .contains(dev.mikoto2000.rei.core.policy.ActionCapability.EXECUTE));
    var conversations =
        org.mockito.Mockito.mock(
            dev.mikoto2000.rei.application.session.ShellConversationService.class);
    var command =
        new picocli.CommandLine(new dev.mikoto2000.rei.core.command.RepairCommand(conversations));
    String id = UUID.randomUUID().toString(), hash = "a".repeat(64);
    assertEquals(0, command.execute("apply", id, hash));
    org.mockito.Mockito.verify(conversations).submit("/repair apply " + id + " " + hash);
    assertEquals(2, command.execute("apply", id, "bad"));
    org.mockito.Mockito.verifyNoMoreInteractions(conversations);
  }

  /** 共通の実行基盤で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void readOnlyInspectionCanReviewButCannotApproveAndStartedCrashHasNoReplay() throws Exception {
    fixture();
    var proposal = service(true).propose(owner(), request());
    var reader =
        new AgentRunContext(
            "read",
            "session",
            root,
            project,
            AgentRunContext.RequestSource.SHELL,
            AgentRunContext.Mode.READ_ONLY);
    assertEquals(proposal.id(), service(false).inspect(reader, proposal.id()).id());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service(true)
                .apply(
                    reader,
                    proposal.id(),
                    proposal.receiptSha256(),
                    "/repair apply " + proposal.id() + " " + proposal.receiptSha256(),
                    (p, o, n) -> fail("no reader write")));
    org.springframework.jdbc.core.simple.JdbcClient.create(source())
        .sql("UPDATE diagnosed_repairs SET status='STARTED' WHERE id=?")
        .param(proposal.id())
        .update();
    assertEquals("UNKNOWN", service(true).inspect(owner(), proposal.id()).status());
    assertThrows(
        IllegalStateException.class,
        () ->
            service(true)
                .apply(
                    owner(),
                    proposal.id(),
                    proposal.receiptSha256(),
                    "/repair apply " + proposal.id() + " " + proposal.receiptSha256(),
                    (p, o, n) -> fail("no crash replay")));
  }
}
