package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.mikoto2000.rei.core.project.ProjectContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** テキスト変更集合の結果と状態の契約を検証するテスト。 */
class TextChangeSetTest {
  /** 不明単一利用権の確保防ぐ別のについて、終了日時利用者状態の照合結果を書き込むことを確認する。 */
  @Test
  void unknownSingleClaimBlocksAnotherWriteUntilHumanReconciliation() throws Exception {
    Files.writeString(root.resolve("a.txt"), "old");
    var repository = repository();
    var service = service(repository);
    var old = service.propose(project(), new TextChangeSetService.Request("a.txt", "old", "new"));
    assertTrue(repository.transition(projectId, old.id(), "PROPOSED", "APPLYING"));
    assertEquals("UNKNOWN", service.inspect(project(), old.id()).status());
    var next = service.propose(project(), new TextChangeSetService.Request("a.txt", "old", "next"));
    assertThrows(
        IllegalStateException.class,
        () -> service.apply(project(), next.id(), next.proposalSha256(), writer()));
    var owner = new dev.mikoto2000.rei.core.chat.AgentRunContext("run", "session", root, projectId);
    var reader =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "read",
            "session",
            root,
            projectId,
            dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.SHELL,
            dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.READ_ONLY);
    String command = "/document reconcile-single " + old.id() + " " + old.proposalSha256();
    assertThrows(
        IllegalArgumentException.class,
        () -> service.reconcile(reader, old.id(), old.proposalSha256(), command));
    assertEquals(
        "RECONCILED", service.reconcile(owner, old.id(), old.proposalSha256(), command).status());
    assertEquals(
        "APPLIED", service.apply(project(), next.id(), next.proposalSha256(), writer()).status());
  }

  /** 共通の実行基盤で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void fatalWriterLeavesUnknownAndExactHumanReconciliationNeverReplays() throws Exception {
    Files.writeString(root.resolve("a.txt"), "old");
    var service = service(repository());
    var proposal =
        service.propose(project(), new TextChangeSetService.Request("a.txt", "old", "new"));
    assertThrows(
        AssertionError.class,
        () ->
            service.apply(
                project(),
                proposal.id(),
                proposal.proposalSha256(),
                (p, o, n) -> {
                  Files.writeString(p, "partial");
                  throw new AssertionError("lost writer");
                }));
    assertEquals("UNKNOWN", service.inspect(project(), proposal.id()).status());
    var owner = new dev.mikoto2000.rei.core.chat.AgentRunContext("run", "session", root, projectId);
    String command =
        "/document reconcile-single " + proposal.id() + " " + proposal.proposalSha256();
    assertThrows(
        IllegalStateException.class,
        () -> service.reconcile(owner, proposal.id(), proposal.proposalSha256(), command));
    assertEquals("partial", Files.readString(root.resolve("a.txt")));
    Files.writeString(root.resolve("a.txt"), "old");
    assertThrows(
        IllegalArgumentException.class,
        () -> service.reconcile(owner, proposal.id(), proposal.proposalSha256(), "please fix"));
    assertEquals(
        "RECONCILED",
        service.reconcile(owner, proposal.id(), proposal.proposalSha256(), command).status());
    assertEquals(
        "RECONCILED",
        service.reconcile(owner, proposal.id(), proposal.proposalSha256(), command).status());
    assertThrows(
        IllegalStateException.class,
        () ->
            service.apply(
                project(),
                proposal.id(),
                proposal.proposalSha256(),
                (p, o, n) -> fail("no replay")));
    assertEquals("old", Files.readString(root.resolve("a.txt")));
  }

  /** 共通の実行基盤で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void guardedHistoricalReceiptSurvivesOrdinaryTerminalPruning() {
    var repository = repository();
    var old =
        new TextChangeSetRepository.Saved(
            UUID.randomUUID().toString(),
            projectId,
            root.toString(),
            "a.txt",
            "a",
            "b",
            "a",
            "b",
            "sha",
            "APPLIED",
            at);
    repository.save(old);
    repository.protectDiagnosed(projectId, old.id());
    for (int i = 1; i <= 110; i++)
      repository.save(
          new TextChangeSetRepository.Saved(
              UUID.randomUUID().toString(),
              projectId,
              root.toString(),
              "a.txt",
              "a",
              "b",
              "a",
              "b",
              "sha",
              "APPLIED",
              at.plusSeconds(i)));
    assertEquals(old, repository.get(projectId, old.id()));
  }

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使う所属プロジェクトの識別子。 */
  final String projectId = UUID.randomUUID().toString();

  /** 検証に使う日時。 */
  final Instant at = Instant.parse("2026-10-05T10:00:00Z");

  /** 検証で使用するプロジェクトを、指定された条件に合わせて用意する。 */
  ProjectContext project() {
    return new ProjectContext(projectId, "rei", root);
  }

  /** 検証で使用する保存と検索の窓口を、指定された条件に合わせて用意する。 */
  TextChangeSetRepository repository() {
    return new TextChangeSetRepository(
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:sqlite:" + root.resolve("changes.db")));
  }

  /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
  TextChangeSetService service(TextChangeSetRepository repository) {
    return new TextChangeSetService(
        repository, Clock.fixed(at, ZoneOffset.UTC), () -> UUID.randomUUID().toString());
  }

  /** {@code writer} の条件を再現するための補助情報を返す。 */
  TextChangeSetService.Writer writer() {
    return (path, oldText, newText) -> Files.writeString(path, newText);
  }

  /** 共通の実行基盤で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void previewPersistsDiffWithoutWritingAndExplicitApplyUsesSavedProposal() throws Exception {
    var file = root.resolve("diagram.mmd");
    Files.writeString(file, "graph TD\n  A-->B\n");
    var service = service(repository());
    var proposal =
        service.propose(
            project(),
            new TextChangeSetService.Request(
                "diagram.mmd", "graph TD\n  A-->B\n", "graph TD\n  A-->C\n"));
    assertEquals("PROPOSED", proposal.status());
    assertTrue(proposal.diff().contains("-  A-->B"));
    assertTrue(proposal.diff().contains("+  A-->C"));
    assertEquals("graph TD\n  A-->B\n", Files.readString(file));
    var restarted = service(repository());
    assertEquals(proposal, restarted.inspect(project(), proposal.id()));
    assertThrows(
        IllegalArgumentException.class,
        () -> restarted.apply(project(), proposal.id(), "wrong", writer()));
    var receipt = restarted.apply(project(), proposal.id(), proposal.proposalSha256(), writer());
    assertEquals("APPLIED", receipt.status());
    assertEquals(receipt.proposedSha256(), receipt.currentSha256());
    assertEquals("graph TD\n  A-->C\n", Files.readString(file));
    assertEquals(
        "APPLIED",
        restarted
            .apply(
                project(), proposal.id(), proposal.proposalSha256(), (p, o, n) -> fail("no replay"))
            .status());
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void refusesStaleContentAndForeignOwnerBeforeWriter() throws Exception {
    Files.writeString(root.resolve("note.md"), "old\n");
    var service = service(repository());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.propose(
                project(), new TextChangeSetService.Request("note.md", "other", "new")));
    var proposal =
        service.propose(project(), new TextChangeSetService.Request("note.md", "old\n", "new\n"));
    Files.writeString(root.resolve("note.md"), "human edit\n");
    assertEquals(
        "STALE",
        service
            .apply(
                project(),
                proposal.id(),
                proposal.proposalSha256(),
                (p, o, n) -> fail("no stale write"))
            .status());
    assertEquals("human edit\n", Files.readString(root.resolve("note.md")));
    var other = new ProjectContext(UUID.randomUUID().toString(), "other", root);
    assertThrows(IllegalArgumentException.class, () -> service.inspect(other, proposal.id()));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.apply(other, proposal.id(), proposal.proposalSha256(), writer()));
  }

  /** 共通の実行基盤の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void failedOrInterruptedApplyIsNotReplayedAfterRestart() throws Exception {
    Files.writeString(root.resolve("note.md"), "old");
    var service = service(repository());
    var proposal =
        service.propose(project(), new TextChangeSetService.Request("note.md", "old", "new"));
    assertThrows(
        java.io.IOException.class,
        () ->
            service.apply(
                project(),
                proposal.id(),
                proposal.proposalSha256(),
                (p, o, n) -> {
                  Files.writeString(p, "partial");
                  throw new java.io.IOException("interrupted");
                }));
    var restarted = service(repository());
    assertEquals("FAILED_UNCERTAIN", restarted.inspect(project(), proposal.id()).status());
    assertThrows(
        IllegalStateException.class,
        () ->
            restarted.apply(
                project(),
                proposal.id(),
                proposal.proposalSha256(),
                (p, o, n) -> fail("no uncertain replay")));
    assertEquals("partial", Files.readString(root.resolve("note.md")));
  }

  /** 共通の実行基盤で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void boundsTextAndRejectsSensitiveOutsideOrBinaryFiles() throws Exception {
    var service = service(repository());
    Files.writeString(root.resolve(".env"), "secret");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.propose(project(), new TextChangeSetService.Request(".env", "secret", "new")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.propose(
                project(), new TextChangeSetService.Request("../outside", "old", "new")));
    Files.write(root.resolve("binary.txt"), new byte[] {0, 1, 2});
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.propose(
                project(), new TextChangeSetService.Request("binary.txt", "old", "new")));
    Files.writeString(root.resolve("note.md"), "old");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.propose(
                project(), new TextChangeSetService.Request("note.md", "old", "x".repeat(65537))));
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void discardAndSavedApplyingStateNeverInvokeWriterAndMissingFileStillAllowsInspection()
      throws Exception {
    Files.writeString(root.resolve("note.md"), "old");
    var repository = repository();
    var service = service(repository);
    var proposal =
        service.propose(project(), new TextChangeSetService.Request("note.md", "old", "new"));
    assertEquals(
        "DISCARDED", service.discard(project(), proposal.id(), proposal.proposalSha256()).status());
    assertThrows(
        IllegalStateException.class,
        () ->
            service.apply(
                project(),
                proposal.id(),
                proposal.proposalSha256(),
                (p, o, n) -> fail("no discarded write")));
    var pending =
        service.propose(project(), new TextChangeSetService.Request("note.md", "old", "other"));
    assertTrue(repository.transition(projectId, pending.id(), "PROPOSED", "APPLYING"));
    var restarted = service(repository());
    assertEquals("UNKNOWN", restarted.inspect(project(), pending.id()).status());
    assertThrows(
        IllegalStateException.class,
        () ->
            restarted.apply(
                project(),
                pending.id(),
                pending.proposalSha256(),
                (p, o, n) -> fail("no startup replay")));
    Files.delete(root.resolve("note.md"));
    assertNull(restarted.inspect(project(), pending.id()).currentSha256());
    assertEquals("UNKNOWN", restarted.inspect(project(), pending.id()).status());
  }

  /** 共通の実行基盤で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void competingServiceCannotClaimProposalDuringWriteAndRootChangesAreRejected() throws Exception {
    Files.writeString(root.resolve("note.md"), "old");
    var service = service(repository());
    var second = service(repository());
    var proposal =
        service.propose(project(), new TextChangeSetService.Request("note.md", "old", "new"));
    var receipt =
        service.apply(
            project(),
            proposal.id(),
            proposal.proposalSha256(),
            (path, oldText, newText) -> {
              assertThrows(
                  IllegalStateException.class,
                  () ->
                      second.apply(
                          project(),
                          proposal.id(),
                          proposal.proposalSha256(),
                          (p, o, n) -> fail("no concurrent write")));
              Files.writeString(path, newText);
            });
    assertEquals("APPLIED", receipt.status());
    var relocated =
        new ProjectContext(projectId, "rei", Files.createDirectory(root.resolve("relocated")));
    assertThrows(IllegalArgumentException.class, () -> second.inspect(relocated, proposal.id()));
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void baselineReadPreservesBomCrLfAndMissingFinalNewlineWithoutSavingProposal() throws Exception {
    var text = "\ufefftitle\r\nbody";
    Files.writeString(root.resolve("note.md"), text);
    var service = service(repository());
    var baseline = service.readBase(project(), "note.md");
    assertEquals(text, baseline.text());
    assertEquals("note.md", baseline.path());
    var proposal =
        service.propose(
            project(),
            new TextChangeSetService.Request(
                baseline.path(), baseline.text(), "\ufefftitle\r\nnew body"));
    assertEquals(baseline.sha256(), proposal.baselineSha256());
    assertTrue(proposal.diff().contains("\\ No newline at end of file"));
  }
}
