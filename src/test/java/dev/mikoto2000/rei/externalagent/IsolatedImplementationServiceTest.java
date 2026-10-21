package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.SelfPatchReviewService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 独立した実装サービスの結果と状態の契約を検証するテスト。 */
class IsolatedImplementationServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temporary;

  /** 検証に使う作業ルート。 */
  Path root;

  /** 検証に使う所有者。 */
  AgentRunContext owner;

  /** 検証に使うサービス。 */
  IsolatedImplementationService service;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() throws Exception {
    root = Files.createDirectory(temporary.resolve("project"));
    git(root, "init", "--quiet");
    git(root, "config", "core.autocrlf", "false");
    Files.writeString(root.resolve("A.txt"), "before\n");
    git(root, "add", "--", "A.txt");
    git(root, "commit", "--quiet", "--no-gpg-sign", "-m", "fixture");
    owner = new AgentRunContext("run", "session", root, "project");
    service =
        new IsolatedImplementationService(
            temporary.resolve("private"),
            new ExternalAgentProcessRunner(),
            new SelfPatchReviewService(new dev.mikoto2000.rei.core.service.SystemShellService()));
  }

  /** {@code command} の条件を再現するための補助情報を返す。 */
  String command() {
    return System.getProperty("os.name").startsWith("Windows") ? "exit 0" : "true";
  }

  /** {@code implement} の条件を再現するための補助情報を返す。 */
  IsolatedImplementationService.Receipt implement() throws Exception {
    return service.implement(
        owner,
        "A.txt",
        command(),
        10,
        () -> false,
        (worktree, manifest) ->
            new ImplementationProposal(
                List.of(
                    new ImplementationProposal.Edit("A.txt", manifest.get("A.txt"), "after\n"))));
  }

  /** 再開の後も対象の情報が残ることを確認する。 */
  @Test
  void parentStaysUnchangedUntilExplicitVerifiedMergeAndReceiptSurvivesRestart() throws Exception {
    var receipt = implement();
    assertEquals("READY_FOR_APPROVAL", receipt.status(), receipt.toString());
    assertEquals("before\n", Files.readString(root.resolve("A.txt")));
    assertEquals(List.of("A.txt"), receipt.changedFiles());
    assertEquals("VERIFIED_CHECKS", receipt.verification().status());
    assertNotNull(receipt.commitHash());
    assertEquals(64, receipt.patchHash().length());
    var preview = service.preview(owner, receipt.id());
    assertEquals(receipt.patchHash(), preview.patchHash());
    assertTrue(preview.diff().contains("+after"));
    assertTrue(preview.diff().contains("-before"));
    var restarted =
        new IsolatedImplementationService(
            temporary.resolve("private"),
            new ExternalAgentProcessRunner(),
            new SelfPatchReviewService(new dev.mikoto2000.rei.core.service.SystemShellService()));
    assertEquals(receipt, restarted.get(owner, receipt.id()));
    var reader =
        new AgentRunContext(
            "read",
            "session",
            root,
            "project",
            AgentRunContext.RequestSource.SHELL,
            AgentRunContext.Mode.READ_ONLY);
    assertEquals(receipt, restarted.get(reader, receipt.id()));
    assertTrue(restarted.preview(reader, receipt.id()).diff().contains("+after"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            restarted.merge(
                reader,
                receipt.id(),
                receipt.patchHash(),
                "/agent codex merge " + receipt.id() + " " + receipt.patchHash()));
    Path stored = temporary.resolve("private").resolve(receipt.id() + ".json");
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var legacy =
        (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readString(stored));
    legacy.remove("provider");
    Files.writeString(stored, json.writeValueAsString(legacy));
    assertEquals("codex", restarted.get(owner, receipt.id()).provider());
    assertThrows(
        IllegalArgumentException.class,
        () -> restarted.merge(owner, receipt.id(), receipt.patchHash(), "ordinary review"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            restarted.merge(
                owner,
                receipt.id(),
                "0".repeat(64),
                "/agent codex merge " + receipt.id() + " " + receipt.patchHash()));
    var merged =
        restarted.merge(
            owner,
            receipt.id(),
            receipt.patchHash(),
            "/agent codex merge " + receipt.id() + " " + receipt.patchHash());
    assertEquals("MERGED", merged.status());
    assertEquals("after\n", Files.readString(root.resolve("A.txt")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            restarted.merge(
                owner,
                receipt.id(),
                receipt.patchHash(),
                "/agent codex merge " + receipt.id() + " " + receipt.patchHash()));
  }

  /** 外部エージェントへの委譲で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void dirtyParentAndStaleParentRejectWithoutLosingEdits() throws Exception {
    Files.writeString(root.resolve("A.txt"), "human edit\n");
    assertThrows(IllegalArgumentException.class, this::implement);
    assertEquals("human edit\n", Files.readString(root.resolve("A.txt")));
    Files.writeString(root.resolve("A.txt"), "before\n");
    var receipt = implement();
    Files.writeString(root.resolve("A.txt"), "later human edit\n");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.merge(
                owner,
                receipt.id(),
                receipt.patchHash(),
                "/agent codex merge " + receipt.id() + " " + receipt.patchHash()));
    assertEquals("later human edit\n", Files.readString(root.resolve("A.txt")));
  }

  /** 外部エージェントへの委譲の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void failedTestsUnexpectedFilesAndForeignOwnershipNeverAuthorizeMerge() throws Exception {
    var bad =
        service.implement(
            owner,
            "A.txt",
            "exit 1",
            10,
            () -> false,
            (tree, manifest) ->
                new ImplementationProposal(
                    List.of(
                        new ImplementationProposal.Edit(
                            "A.txt", manifest.get("A.txt"), "after\n"))));
    assertEquals("FAILED", bad.status());
    assertNull(bad.commitHash());
    var unexpected =
        service.implement(
            owner,
            "A.txt",
            command(),
            10,
            () -> false,
            (tree, manifest) -> {
              Files.writeString(tree.resolve("unexpected.txt"), "outside proposal");
              return new ImplementationProposal(
                  List.of(
                      new ImplementationProposal.Edit("A.txt", manifest.get("A.txt"), "after\n")));
            });
    assertEquals("FAILED", unexpected.status());
    assertNull(unexpected.commitHash());
    var other = new AgentRunContext("other", "otherSession", root, "project");
    assertThrows(IllegalArgumentException.class, () -> service.get(other, bad.id()));
    assertEquals("before\n", Files.readString(root.resolve("A.txt")));
  }

  /** 外部エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void modifiedCommittedWorktreeRequiresReReviewBeforeMerge() throws Exception {
    var receipt = implement();
    Files.writeString(Path.of(receipt.worktree()).resolve("A.txt"), "unreviewed edit\n");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.merge(
                owner,
                receipt.id(),
                receipt.patchHash(),
                "/agent codex merge " + receipt.id() + " " + receipt.patchHash()));
    assertEquals("before\n", Files.readString(root.resolve("A.txt")));
  }

  /** 外部エージェントへの委譲の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void aConcurrentConflictingCommitIsDetectedWithoutAbortingOrRetryingTheMerge() throws Exception {
    var receipt = implement();
    var racing =
        new ExternalAgentProcessRunner() {
          /** 検証用に、外部エージェントへの委譲の操作を実行する。 */
          @Override
          public Output run(
              List<String> command,
              Path cwd,
              String input,
              java.time.Duration total,
              java.time.Duration idle,
              int bytes,
              java.util.function.BooleanSupplier cancelled) {
            if (command.contains("merge") && command.contains("--no-ff"))
              try {
                Files.writeString(root.resolve("A.txt"), "concurrent edit\n");
                git(root, "add", "A.txt");
                git(root, "commit", "--quiet", "--no-gpg-sign", "-m", "concurrent fixture");
              } catch (Exception error) {
                throw new RuntimeException(error);
              }
            return super.run(command, cwd, input, total, idle, bytes, cancelled);
          }
        };
    var merger =
        new IsolatedImplementationService(
            temporary.resolve("private"),
            racing,
            new SelfPatchReviewService(new dev.mikoto2000.rei.core.service.SystemShellService()));
    var conflict =
        merger.merge(
            owner,
            receipt.id(),
            receipt.patchHash(),
            "/agent codex merge " + receipt.id() + " " + receipt.patchHash());
    assertEquals("CONFLICT", conflict.status());
    assertTrue(Files.exists(root.resolve(".git/MERGE_HEAD")));
    assertTrue(Files.readString(root.resolve("A.txt")).contains("<<<<<<<"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            merger.merge(
                owner,
                receipt.id(),
                receipt.patchHash(),
                "/agent codex merge " + receipt.id() + " " + receipt.patchHash()));
  }

  /** {@code git} の条件を再現するため、検証用の操作や状態を扱う。 */
  void git(Path directory, String... args) throws Exception {
    var cmd =
        new ArrayList<String>(
            List.of(
                "git",
                "-c",
                "user.name=Fixture",
                "-c",
                "user.email=fixture@example.invalid",
                "-c",
                "core.hooksPath="));
    cmd.addAll(List.of(args));
    var p = new ProcessBuilder(cmd).directory(directory.toFile()).redirectErrorStream(true).start();
    assertTrue(p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(0, p.exitValue(), new String(p.getInputStream().readAllBytes()));
  }
}
