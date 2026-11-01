package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.mikoto2000.rei.core.process.BuildTestFailureDiagnosis;
import dev.mikoto2000.rei.externalagent.ExternalAgentProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Self変更差分レビューGitの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
@org.junit.jupiter.api.extension.ExtendWith(
    dev.mikoto2000.rei.testsupport.FixtureTimingExtension.class)
class SelfPatchReviewGitTest {
  /** The suite owns an immutable seed outside every mutable test repository. */
  @TempDir static Path seedDirectory;

  /** Native Git history reused only through independent file copies. */
  static Path seedRepository;

  /** Pays native Git initialization once without sharing writable repositories. */
  @org.junit.jupiter.api.BeforeAll
  static void prepareSeedRepository() throws Exception {
    seedRepository = Files.createDirectory(seedDirectory.resolve("repository"));
    initializeRepository(seedRepository);
  }

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使う検証一覧。 */
  final AtomicInteger tests = new AtomicInteger();

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void repository() throws Exception {
    copyRepository(seedRepository, root);
  }

  /** Initializes the unchanged native repository seed. */
  static void initializeRepository(Path root) throws Exception {
    git(root, "init", "--quiet");
    git(root, "config", "core.autocrlf", "false");
    Files.writeString(root.resolve(".gitignore"), "build/\n");
    Files.writeString(root.resolve("A.txt"), "before\n");
    git(root, "add", "--", ".gitignore", "A.txt");
    git(root, "commit", "--quiet", "--no-gpg-sign", "-m", "fixture");
  }

  /** {@code git} の条件を再現するため、検証用の操作や状態を扱う。 */
  void git(String... arguments) throws Exception {
    git(root, arguments);
  }

  /** Runs fixture commands with the original timeout, configuration and exit assertions. */
  static String git(Path root, String... arguments) throws Exception {
    var command =
        new ArrayList<String>(
            List.of(
                "git",
                "--no-pager",
                "-c",
                "user.name=Fixture",
                "-c",
                "user.email=fixture@example.invalid",
                "-c",
                "core.autocrlf=false",
                "-c",
                "core.fsmonitor=false"));
    command.addAll(List.of(arguments));
    var process =
        new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
    if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
      process.destroyForcibly();
      fail("fixture git timed out");
    }
    String output =
        new String(
            process.getInputStream().readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(0, process.exitValue(), output);
    return output;
  }

  /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
  SelfPatchReviewService service() {
    var inspector = new GitPatchInspector(new ExternalAgentProcessRunner());
    return new SelfPatchReviewService(
        inspector::capture,
        inspector::review,
        (r, c, t) -> {
          tests.incrementAndGet();
          return new SelfPatchReviewService.TestObservation(
              "completed",
              0,
              false,
              false,
              BuildTestFailureDiagnosis.command("completed", 0, false, "", "", null));
        });
  }

  /** 検証用に、要求された条件を満たすことを確認する。 */
  SelfPatchReviewService.Result verify() throws Exception {
    return service().verify(root, new SelfPatchReviewService.Request("controlled fixture", 10));
  }

  /** 共通の実行基盤の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void diagnosedRepairConnectsRealFailureReportApprovalApplyAndRealShellVerification()
      throws Exception {
    Files.createDirectories(root.resolve("build"));
    Files.writeString(root.resolve("A.txt"), "broken\n");
    String failed =
        "<testsuite tests=\"1\" failures=\"1\" errors=\"0\" skipped=\"0\"><testcase classname=\"Fixture\" name=\"value\"><failure message=\"broken\">assertion</failure></testcase></testsuite>";
    String passed =
        "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase classname=\"Fixture\" name=\"value\"/></testsuite>";
    Files.writeString(root.resolve("build/TEST.xml"), failed);
    String command =
        System.getProperty("os.name").toLowerCase().contains("win")
            ? "Add-Content build/runs.txt run; if ((Get-Content A.txt -Raw).Trim() -eq 'fixed') { Set-Content build/TEST.xml '"
                + passed
                + "'; exit 0 }; Set-Content build/TEST.xml '"
                + failed
                + "'; exit 1"
            : "printf '%s\\n' run >> build/runs.txt; if [ \"$(cat A.txt)\" = fixed ]; then printf '%s' '"
                + passed
                + "' > build/TEST.xml; exit 0; fi; printf '%s' '"
                + failed
                + "' > build/TEST.xml; exit 1";
    var ds =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:sqlite:" + root.resolve("build/changes.db"));
    var service =
        new DiagnosedRepairService(
            ds,
            new TextChangeSetService(new TextChangeSetRepository(ds)),
            java.time.Clock.systemUTC(),
            true,
            new dev.mikoto2000.rei.core.service.SystemShellService());
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "run", "session", root, UUID.randomUUID().toString());
    var proposal =
        service.propose(
            owner,
            new DiagnosedRepairService.Request(
                "build/TEST.xml",
                new TextChangeSetService.Request("A.txt", "broken\n", "fixed\n"),
                command,
                10));
    byte[] index = Files.readAllBytes(root.resolve(".git/index"));
    var result =
        service.apply(
            owner,
            proposal.id(),
            proposal.receiptSha256(),
            "/repair apply " + proposal.id() + " " + proposal.receiptSha256(),
            (p, o, n) -> Files.writeString(p, n));
    assertEquals("VERIFIED_CHECKS", result.status(), result.toString());
    assertEquals(3, Files.readAllLines(root.resolve("build/runs.txt")).size());
    assertEquals("fixed\n", Files.readString(root.resolve("A.txt")));
    assertEquals(1, result.verification().rounds().getFirst().initialTest().exitCode());
    assertEquals(0, result.verification().rounds().getLast().finalTest().exitCode());
    assertTrue(
        new TestReportDiagnosisService().read(root, "build/TEST.xml").failedTests().isEmpty());
    assertArrayEquals(index, Files.readAllBytes(root.resolve(".git/index")));
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void requirementReviewUsesRealGitMaterialAndFreshRealCommandReportWithoutChangingIndex()
      throws Exception {
    Files.createDirectories(root.resolve("build"));
    Files.writeString(root.resolve("A.txt"), "after\n");
    String passed =
        "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase classname=\"Fixture\" name=\"value\"/></testsuite>";
    String command =
        System.getProperty("os.name").toLowerCase().contains("win")
            ? "Add-Content build/runs.txt run; Set-Content build/TEST.xml '" + passed + "'; exit 0"
            : "printf '%s\\n' run >> build/runs.txt; printf '%s' '" + passed + "' > build/TEST.xml";
    var ds =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:sqlite:" + root.resolve("build/reviews.db"));
    var service =
        new SemanticPatchReviewService(
            ds,
            java.time.Clock.systemUTC(),
            false,
            new dev.mikoto2000.rei.core.service.SystemShellService(),
            org.mockito.Mockito.mock(dev.mikoto2000.rei.llm.LlmModelProvider.class));
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "run", "session", root, UUID.randomUUID().toString());
    var request =
        new SemanticPatchReviewService.Request(
            command,
            10,
            List.of(
                new SemanticPatchReviewService.Requirement(
                    "R1", "change value", List.of("A.txt"), List.of("Fixture#value"))),
            List.of("A.txt"),
            List.of("build/TEST.xml"),
            false);
    byte[] index = Files.readAllBytes(root.resolve(".git/index"));
    var receipt = service.review(owner, request, null);
    assertEquals("DETERMINISTIC_CHECKS_ONLY", receipt.status(), receipt.toString());
    assertEquals(2, Files.readAllLines(root.resolve("build/runs.txt")).size());
    assertArrayEquals(index, Files.readAllBytes(root.resolve(".git/index")));
    assertEquals(List.of("Fixture#value"), receipt.detail().tests().getFirst().passedTests());
    assertEquals(receipt, service.get(owner, receipt.id(), receipt.sha256()));
    assertEquals(receipt, service.review(owner, request, null));
    assertEquals(2, Files.readAllLines(root.resolve("build/runs.txt")).size());
    var goals = new dev.mikoto2000.rei.goal.GoalRepository(ds, java.time.Clock.systemUTC());
    var verifier = new dev.mikoto2000.rei.goal.FileGoalVerifier();
    var artifacts =
        org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
    var gate =
        new dev.mikoto2000.rei.goal.GoalCompletionGate(
            goals, service, artifacts, java.time.Clock.systemUTC(), false);
    verifier.setCompletionGate(gate);
    var goal =
        goals.create(
            owner, "change value", "A.txt", receipt.detail().files().getFirst().sha256(), 2, 5);
    goals.defineCompletion(
        owner,
        goal.id(),
        new dev.mikoto2000.rei.goal.GoalCompletionGate.Definition(
            goal.criteria(),
            new dev.mikoto2000.rei.goal.GoalCompletionGate.RequiredTests(
                receipt.detail().commandSha256(), List.of("Fixture#value")),
            List.of(),
            List.of(),
            new dev.mikoto2000.rei.goal.GoalCompletionGate.ReviewGate(
                false, request.requirements())));
    var loop =
        new dev.mikoto2000.rei.goal.GoalLoopService(
            goals,
            verifier,
            (claim, run, done) -> fail("saved proof needs no model"),
            new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true, null, null, null),
            new dev.mikoto2000.rei.goal.GoalEvents(event -> {}, java.time.Clock.systemUTC()));
    assertEquals("READY", loop.verify(owner.projectId(), goal.id()).goal().status());
    gate.attach(
        owner,
        goal.id(),
        new dev.mikoto2000.rei.goal.GoalCompletionGate.Proof(
            new dev.mikoto2000.rei.goal.GoalCompletionGate.Reference(
                receipt.id(), receipt.sha256()),
            List.of()),
        true);
    assertEquals("COMPLETED", loop.verify(owner.projectId(), goal.id()).goal().status());
    assertEquals("completion_gate_verified", goals.get(owner.projectId(), goal.id()).reason());
    assertArrayEquals(index, Files.readAllBytes(root.resolve(".git/index")));
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void savedChangeSetRepairsRealGitFindingThenRunsFinalRealShellTest() throws Exception {
    Files.createDirectories(root.resolve("build"));
    Files.writeString(root.resolve("A.txt"), "after \n");
    var project =
        new dev.mikoto2000.rei.core.project.ProjectContext(
            UUID.randomUUID().toString(), "fixture", root);
    var projects = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    org.mockito.Mockito.when(projects.currentContext()).thenReturn(project);
    org.mockito.Mockito.when(projects.currentProject()).thenReturn(root);
    var tools = new Tools(projects, new dev.mikoto2000.rei.core.service.SystemShellService());
    var ds =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:sqlite:" + root.resolve("build/changes.db"));
    tools.setTextChangeSets(new TextChangeSetService(new TextChangeSetRepository(ds)));
    var proposal =
        tools.proposeTextChangeSet(
            new TextChangeSetService.Request("A.txt", "after \n", "after\n"));
    byte[] index = Files.readAllBytes(root.resolve(".git/index"));
    String command =
        System.getProperty("os.name").toLowerCase().contains("win")
            ? "Add-Content build/runs.txt 'run'; exit 0"
            : "printf '%s\\n' run >> build/runs.txt";
    var result =
        tools.selfRepairPatch(
            new SelfPatchRepairService.Request(
                command,
                10,
                List.of(
                    new SelfPatchRepairService.Repair(proposal.id(), proposal.proposalSha256()))));
    assertEquals("VERIFIED_CHECKS", result.status(), result.toString());
    assertEquals(2, result.rounds().size());
    assertEquals("FIX_REQUIRED", result.rounds().getFirst().status());
    assertEquals("APPLIED", result.repairs().getFirst().status());
    assertEquals("after\n", Files.readString(root.resolve("A.txt")));
    assertEquals(3, Files.readAllLines(root.resolve("build/runs.txt")).size());
    assertArrayEquals(index, Files.readAllBytes(root.resolve(".git/index")));
  }

  /** 共通の実行基盤で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void realCommandsRunTwiceOnTheSamePatchAndIgnoredOutputsDoNotInvalidateIt() throws Exception {
    Files.writeString(root.resolve("A.txt"), "after\n");
    byte[] index = Files.readAllBytes(root.resolve(".git/index"));
    String command =
        System.getProperty("os.name").toLowerCase().contains("win")
            ? "New-Item -ItemType Directory -Force build | Out-Null; Add-Content build/runs.txt 'run'; exit 0"
            : "mkdir -p build; printf '%s\\n' run >> build/runs.txt";
    var result =
        new SelfPatchReviewService(new dev.mikoto2000.rei.core.service.SystemShellService())
            .verify(root, new SelfPatchReviewService.Request(command, 10));
    assertEquals("VERIFIED_CHECKS", result.status(), result.toString());
    assertEquals(2, Files.readAllLines(root.resolve("build/runs.txt")).size());
    assertArrayEquals(index, Files.readAllBytes(root.resolve(".git/index")));
  }

  /** 登録済み空白について、修正を必要とすることを確認する。 */
  @Test
  void trackedWhitespaceRequiresFixAndACompleteNewCycleAfterFix() throws Exception {
    Files.writeString(root.resolve("A.txt"), "after \n");
    var first = verify();
    assertEquals("FIX_REQUIRED", first.status());
    assertTrue(first.review().findings().stream().anyMatch(f -> f.kind().equals("WHITESPACE")));
    assertEquals(1, tests.get());
    Files.writeString(root.resolve("A.txt"), "after\n");
    assertEquals("VERIFIED_CHECKS", verify().status());
    assertEquals(3, tests.get());
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void untrackedConflictMarkersAreReviewedWithoutAddingFilesToTheIndex() throws Exception {
    byte[] index = Files.readAllBytes(root.resolve(".git/index"));
    Files.writeString(
        root.resolve("New.txt"), "<<<<<<< local\nleft\n=======\nright\n>>>>>>> incoming\n");
    var result = verify();
    assertEquals("FIX_REQUIRED", result.status());
    assertTrue(
        result.review().findings().stream()
            .anyMatch(f -> f.path().equals("New.txt") && f.kind().equals("CONFLICT_MARKER")));
    assertArrayEquals(index, Files.readAllBytes(root.resolve(".git/index")));
  }

  /** 共通の実行基盤で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void binarySecretsOversizedFilesAndSubdirectoryRootsFailClosedBeforeTests() throws Exception {
    Files.write(root.resolve("New.bin"), new byte[] {0, 1});
    assertEquals("BLOCKED", verify().status());
    Files.delete(root.resolve("New.bin"));
    Files.writeString(root.resolve(".env"), "token=private\n");
    assertEquals("BLOCKED", verify().status());
    Files.delete(root.resolve(".env"));
    Files.writeString(root.resolve("Large.txt"), "x".repeat(270000));
    assertEquals("BLOCKED", verify().status());
    Files.delete(root.resolve("Large.txt"));
    Path sub = Files.createDirectory(root.resolve("nested"));
    assertEquals(
        "BLOCKED",
        service().verify(sub, new SelfPatchReviewService.Request("fixture", 10)).status());
    assertEquals(0, tests.get());
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void stagingAndDeletionRefreshTheFingerprint() throws Exception {
    Files.writeString(root.resolve("A.txt"), "after\n");
    var inspector = new GitPatchInspector(new ExternalAgentProcessRunner());
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    var first = inspector.capture(root, deadline);
    git("add", "--", "A.txt");
    var staged = inspector.capture(root, deadline);
    assertNotEquals(first.version(), staged.version());
    Files.delete(root.resolve("A.txt"));
    var deleted = inspector.capture(root, deadline);
    assertTrue(deleted.complete());
    assertNotEquals(staged.version(), deleted.version());
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void repositoryExternalDiffAndFsmonitorConfigurationAreNotExecuted() throws Exception {
    git("config", "diff.external", "definitely-missing-diff-helper");
    git("config", "core.fsmonitor", "definitely-missing-fsmonitor-helper");
    Files.writeString(root.resolve("A.txt"), "after\n");
    assertEquals("VERIFIED_CHECKS", verify().status());
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void noPatchAndTooManyFilesDoNotStartTests() throws Exception {
    assertEquals("NO_PATCH", verify().status());
    for (int i = 0; i < 129; i++) Files.writeString(root.resolve("New" + i + ".txt"), "new\n");
    assertEquals("BLOCKED", verify().status());
    assertEquals(0, tests.get());
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void successfulExitWithTruncatedRealLogsCannotVerify() throws Exception {
    Files.writeString(root.resolve("A.txt"), "after\n");
    String command =
        System.getProperty("os.name").toLowerCase().contains("win")
            ? "Write-Output ('x' * 70000); exit 0"
            : "printf '%70000s' ''";
    var result =
        new SelfPatchReviewService(new dev.mikoto2000.rei.core.service.SystemShellService())
            .verify(root, new SelfPatchReviewService.Request(command, 10));
    assertEquals("INITIAL_TEST_FAILED", result.status());
    assertTrue(result.initialTest().logsTruncated());
    assertNull(result.finalTest());
  }

  /** 共通の実行基盤で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void realTestTimeoutStopsTheCycleBeforeReviewAndFinalTest() throws Exception {
    Files.writeString(root.resolve("A.txt"), "after\n");
    String command =
        System.getProperty("os.name").toLowerCase().contains("win")
            ? "Start-Sleep -Seconds 3; exit 0"
            : "sleep 3";
    var result =
        new SelfPatchReviewService(new dev.mikoto2000.rei.core.service.SystemShellService())
            .verify(root, new SelfPatchReviewService.Request(command, 1));
    assertEquals("INITIAL_TEST_FAILED", result.status());
    assertTrue(result.initialTest().timedOut());
    assertNull(result.review());
    assertNull(result.finalTest());
  }

  /** Copies must be independent before the seed can be safely reused. */
  static void copyRepository(Path source, Path destination) throws Exception {
    try (var entries = Files.walk(source)) {
      for (var entry : entries.toList()) {
        Path target = destination.resolve(source.relativize(entry));
        if (Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
          Files.createDirectories(target);
        } else {
          if (!Files.isRegularFile(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new java.io.IOException("Fixture seed must contain only regular files");
          Files.copy(entry, target, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
        }
      }
    }
    // A new file identity must not make a later read-only review refresh the copied index.
    git(destination, "update-index", "--refresh");
  }
}
