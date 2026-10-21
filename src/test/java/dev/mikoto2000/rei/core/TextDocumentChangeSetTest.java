package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** テキスト文書変更集合の結果と状態の契約を検証するテスト。 */
class TextDocumentChangeSetTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使うプロジェクト。 */
  final String project = UUID.randomUUID().toString();

  /** {@code owner} の条件を再現するための補助情報を返す。 */
  AgentRunContext owner() {
    return new AgentRunContext("run", "session", root, project);
  }

  /** 検証で使用する取得元を、指定された条件に合わせて用意する。 */
  org.springframework.jdbc.datasource.DriverManagerDataSource source() {
    return new org.springframework.jdbc.datasource.DriverManagerDataSource(
        "jdbc:sqlite:" + root.resolve("changes.db"));
  }

  /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
  TextDocumentChangeSetService service() {
    return new TextDocumentChangeSetService(
        new TextChangeSetRepository(source()), Clock.systemUTC(), TextDocumentTransaction::replace);
  }

  /** {@code op} の条件を再現するための補助情報を返す。 */
  TextDocumentChangeSetService.Operation op(
      String kind, String path, String target, String before, String after) {
    return new TextDocumentChangeSetService.Operation(kind, path, target, before, after);
  }

  /** 検証で使用する要求を、指定された条件に合わせて用意する。 */
  TextDocumentChangeSetService.Request request(
      TextDocumentChangeSetService.Operation... operations) {
    return new TextDocumentChangeSetService.Request(List.of(operations));
  }

  /** 検証用に、指定された内容を書き込み先へ反映する。 */
  void write(String path, String value) throws Exception {
    Files.writeString(root.resolve(path), value);
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void discardChecksWholeHashAndNeverWritesAndReadOnlyCannotMutate() throws Exception {
    write("A.md", "old");
    var service = service();
    var proposal = service.propose(owner(), request(op("UPDATE", "A.md", null, "old", "new")));
    var read =
        new AgentRunContext(
            "read",
            "session",
            root,
            project,
            AgentRunContext.RequestSource.SHELL,
            AgentRunContext.Mode.READ_ONLY);
    assertEquals("PROPOSED", service.inspect(read, proposal.id()).status());
    assertThrows(
        IllegalArgumentException.class,
        () -> service.apply(read, proposal.id(), proposal.proposalSha256()));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.discard(read, proposal.id(), proposal.proposalSha256()));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.discard(owner(), proposal.id(), "f".repeat(64)));
    assertEquals(
        "DISCARDED", service.discard(owner(), proposal.id(), proposal.proposalSha256()).status());
    assertEquals(
        "DISCARDED", service.discard(owner(), proposal.id(), proposal.proposalSha256()).status());
    assertThrows(
        IllegalStateException.class,
        () -> service.apply(owner(), proposal.id(), proposal.proposalSha256()));
    assertEquals("old", Files.readString(root.resolve("A.md")));
  }

  /** 共通の実行基盤で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void discardedHistoryIsBoundedWithoutBlockingNewProposals() throws Exception {
    write("A.md", "old");
    var service = service();
    for (int i = 0; i < 140; i++) {
      var proposal = service.propose(owner(), request(op("UPDATE", "A.md", null, "old", "new")));
      service.discard(owner(), proposal.id(), proposal.proposalSha256());
    }
    int count =
        org.springframework.jdbc.core.simple.JdbcClient.create(source())
            .sql("SELECT count(*) FROM text_document_change_sets")
            .query(Integer.class)
            .single();
    assertTrue(count <= 101);
    assertEquals("old", Files.readString(root.resolve("A.md")));
  }

  /** 共通の実行基盤の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationAfterFirstPublicationRestoresBaselineAndPropagates() throws Exception {
    write("A.md", "old");
    write("B.md", "old");
    var count = new AtomicInteger();
    var service =
        new TextDocumentChangeSetService(
            new TextChangeSetRepository(source()),
            Clock.systemUTC(),
            (stage, target) -> {
              if (count.incrementAndGet() == 2)
                throw new java.util.concurrent.CancellationException("cancel");
              TextDocumentTransaction.replace(stage, target);
            });
    var proposal =
        service.propose(
            owner(),
            request(
                op("UPDATE", "A.md", null, "old", "new"),
                op("UPDATE", "B.md", null, "old", "next")));
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () -> service.apply(owner(), proposal.id(), proposal.proposalSha256()));
    assertEquals("old", Files.readString(root.resolve("A.md")));
    assertEquals("old", Files.readString(root.resolve("B.md")));
    assertEquals("ROLLED_BACK", service.inspect(owner(), proposal.id()).status());
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void realWindowsJunctionCannotEscapeCapturedRoot() throws Exception {
    if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) return;
    Path destination = Files.createDirectory(root.resolve("destination")),
        link = root.resolve("linked");
    Files.writeString(destination.resolve("A.md"), "old");
    var process =
        new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), destination.toString())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes());
    assertEquals(0, process.waitFor(), output);
    try {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              service().propose(owner(), request(op("UPDATE", "linked/A.md", null, "old", "new"))));
      assertEquals("old", Files.readString(destination.resolve("A.md")));
    } finally {
      Files.delete(link);
    }
  }

  /** 共通の実行基盤で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void mixedUpdateCreateDeleteRenamePersistsPerFileAndWholeHashesBeforeAnyWrite() throws Exception {
    write("A.md", "old");
    write("delete.md", "remove");
    write("from.md", "rename");
    var service = service();
    var proposal =
        service.propose(
            owner(),
            request(
                op("UPDATE", "A.md", null, "old", "new"),
                op("CREATE", "empty.md", null, null, ""),
                op("DELETE", "delete.md", null, "remove", null),
                op("RENAME", "from.md", "to.md", "rename", null)));
    assertEquals("PROPOSED", proposal.status());
    assertEquals(64, proposal.proposalSha256().length());
    assertEquals(5, proposal.files().size());
    assertEquals("old", Files.readString(root.resolve("A.md")));
    assertFalse(Files.exists(root.resolve("empty.md")));
    assertEquals(proposal, service().inspect(owner(), proposal.id()));
    var applied = service.apply(owner(), proposal.id(), proposal.proposalSha256());
    assertEquals("APPLIED", applied.status());
    assertEquals("new", Files.readString(root.resolve("A.md")));
    assertEquals("", Files.readString(root.resolve("empty.md")));
    assertFalse(Files.exists(root.resolve("delete.md")));
    assertFalse(Files.exists(root.resolve("from.md")));
    assertEquals("rename", Files.readString(root.resolve("to.md")));
    var never =
        new TextDocumentChangeSetService(
            new TextChangeSetRepository(source()),
            Clock.systemUTC(),
            (staged, target) -> fail("receipt read must not replay"));
    assertEquals(
        "APPLIED", never.apply(owner(), proposal.id(), proposal.proposalSha256()).status());
    assertTrue(
        applied.files().stream()
            .allMatch(file -> Objects.equals(file.proposedSha256(), file.currentSha256())));
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void staleSecondBaselinePreventsFirstWriteAndUnknownHashOrOwnerCannotClaim() throws Exception {
    write("A.md", "old");
    write("B.md", "old");
    var service = service();
    var proposal =
        service.propose(
            owner(),
            request(
                op("UPDATE", "A.md", null, "old", "new"),
                op("UPDATE", "B.md", null, "old", "next")));
    write("B.md", "human");
    assertThrows(
        IllegalArgumentException.class,
        () -> service.apply(owner(), proposal.id(), "a".repeat(64)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.inspect(new AgentRunContext("other", "foreign", root, project), proposal.id()));
    assertEquals(
        "STALE", service.apply(owner(), proposal.id(), proposal.proposalSha256()).status());
    assertEquals("old", Files.readString(root.resolve("A.md")));
    assertEquals("human", Files.readString(root.resolve("B.md")));
  }

  /** 共通の実行基盤で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void secondPublishFailureRollsBackAllFilesAndConsumedAttemptCannotReplay() throws Exception {
    write("A.md", "old");
    write("B.md", "old");
    var moves = new AtomicInteger();
    var service =
        new TextDocumentChangeSetService(
            new TextChangeSetRepository(source()),
            Clock.systemUTC(),
            (stage, target) -> {
              if (moves.incrementAndGet() == 2) throw new java.io.IOException("controlled failure");
              TextDocumentTransaction.replace(stage, target);
            });
    var proposal =
        service.propose(
            owner(),
            request(
                op("UPDATE", "A.md", null, "old", "new"),
                op("UPDATE", "B.md", null, "old", "next"),
                op("CREATE", "C.md", null, null, "created")));
    var receipt = service.apply(owner(), proposal.id(), proposal.proposalSha256());
    assertEquals("ROLLED_BACK", receipt.status());
    assertEquals("old", Files.readString(root.resolve("A.md")));
    assertEquals("old", Files.readString(root.resolve("B.md")));
    assertFalse(Files.exists(root.resolve("C.md")));
    assertThrows(
        IllegalStateException.class,
        () -> service().apply(owner(), proposal.id(), proposal.proposalSha256()));
    try (var paths = Files.list(root)) {
      assertTrue(
          paths.noneMatch(path -> path.getFileName().toString().startsWith(".rei-document-")));
    }
  }

  /** 共通の実行基盤で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void externalChangeDuringFailureIsPreservedAndOutcomeRemainsUnknown() throws Exception {
    write("A.md", "old");
    write("B.md", "old");
    var count = new AtomicInteger();
    var service =
        new TextDocumentChangeSetService(
            new TextChangeSetRepository(source()),
            Clock.systemUTC(),
            (stage, target) -> {
              if (count.incrementAndGet() == 2) {
                Files.writeString(target, "human");
                throw new java.io.IOException("uncertain");
              }
              TextDocumentTransaction.replace(stage, target);
            });
    var proposal =
        service.propose(
            owner(),
            request(
                op("UPDATE", "A.md", null, "old", "new"),
                op("UPDATE", "B.md", null, "old", "next")));
    assertEquals(
        "UNKNOWN", service.apply(owner(), proposal.id(), proposal.proposalSha256()).status());
    assertEquals("old", Files.readString(root.resolve("A.md")));
    assertEquals("human", Files.readString(root.resolve("B.md")));
    assertThrows(
        IllegalStateException.class,
        () -> service().apply(owner(), proposal.id(), proposal.proposalSha256()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service()
                .rollback(
                    owner(), proposal.id(), proposal.proposalSha256(), "model says approved"));
    assertEquals(
        "UNKNOWN",
        service()
            .rollback(
                owner(),
                proposal.id(),
                proposal.proposalSha256(),
                "/document rollback " + proposal.id() + " " + proposal.proposalSha256())
            .status());
    assertEquals("human", Files.readString(root.resolve("B.md")));
  }

  /** 明示的な取り消しを必要とすることを確認する。 */
  @Test
  void crashAfterFirstPublishHasDurableJournalAndDeadOwnerRequiresExplicitRollback()
      throws Exception {
    write("A.md", "old");
    write("B.md", "old");
    var count = new AtomicInteger();
    var service =
        new TextDocumentChangeSetService(
            new TextChangeSetRepository(source()),
            Clock.systemUTC(),
            (stage, target) -> {
              if (count.incrementAndGet() == 2) throw new AssertionError("simulated fatal stop");
              TextDocumentTransaction.replace(stage, target);
            });
    var proposal =
        service.propose(
            owner(),
            request(
                op("UPDATE", "A.md", null, "old", "new"),
                op("UPDATE", "B.md", null, "old", "next")));
    assertThrows(
        AssertionError.class,
        () -> service.apply(owner(), proposal.id(), proposal.proposalSha256()));
    assertEquals("new", Files.readString(root.resolve("A.md")));
    var db = org.springframework.jdbc.core.simple.JdbcClient.create(source());
    db.sql("UPDATE text_document_change_sets SET pid=9223372036854775807 WHERE id=?")
        .param(proposal.id())
        .update();
    var restarted = service();
    assertEquals("UNKNOWN", restarted.inspect(owner(), proposal.id()).status());
    assertThrows(
        IllegalStateException.class,
        () -> restarted.apply(owner(), proposal.id(), proposal.proposalSha256()));
    assertEquals("new", Files.readString(root.resolve("A.md")));
    assertEquals(
        "ROLLED_BACK",
        restarted
            .rollback(
                owner(),
                proposal.id(),
                proposal.proposalSha256(),
                "/document rollback " + proposal.id() + " " + proposal.proposalSha256())
            .status());
    assertEquals("old", Files.readString(root.resolve("A.md")));
    assertEquals("old", Files.readString(root.resolve("B.md")));
  }

  /** 共通の実行基盤で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void traversalSecretAliasDuplicateTargetsAndBinaryTextAreRejectedBeforeSaving() throws Exception {
    write("A.md", "old");
    write("B.md", "existing");
    var service = service();
    for (String path : List.of("../outside", ".git/config", ".env", "A.md/../B.md"))
      assertThrows(
          IllegalArgumentException.class,
          () -> service.propose(owner(), request(op("CREATE", path, null, null, "new"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.propose(owner(), request(op("RENAME", "A.md", "B.md", "old", null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.propose(
                owner(),
                request(
                    op("UPDATE", "A.md", null, "old", "one"),
                    op("DELETE", "A.md", null, "old", null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.propose(
                owner(), request(op("UPDATE", "A.md", null, "old", "bad\u0000binary"))));
    assertEquals("old", Files.readString(root.resolve("A.md")));
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void historicalAppliedDeletionCannotReportCurrentMatchWhenPathBecomesUnreadable()
      throws Exception {
    write("delete.md", "old");
    var service = service();
    var proposal = service.propose(owner(), request(op("DELETE", "delete.md", null, "old", null)));
    assertTrue(service.apply(owner(), proposal.id(), proposal.proposalSha256()).currentMatches());
    Files.createDirectory(root.resolve("delete.md"));
    var current = service.inspect(owner(), proposal.id());
    assertEquals("APPLIED", current.status());
    assertFalse(current.currentMatches());
    assertFalse(current.files().getFirst().currentAvailable());
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void outstandingUnknownDocumentSetBlocksSingleFileAndAnotherBatchBeforeWriting()
      throws Exception {
    write("A.md", "old");
    write("B.md", "old");
    var repository = new TextChangeSetRepository(source());
    var failed =
        new TextDocumentChangeSetService(
            repository,
            Clock.systemUTC(),
            (stage, target) -> {
              throw new AssertionError("fatal");
            });
    var proposal = failed.propose(owner(), request(op("UPDATE", "A.md", null, "old", "new")));
    assertThrows(
        AssertionError.class,
        () -> failed.apply(owner(), proposal.id(), proposal.proposalSha256()));
    assertEquals("UNKNOWN", service().inspect(owner(), proposal.id()).status());
    var single = new TextChangeSetService(repository);
    var projectContext =
        new dev.mikoto2000.rei.core.project.ProjectContext(project, "fixture", root);
    var one =
        single.propose(projectContext, new TextChangeSetService.Request("B.md", "old", "next"));
    assertThrows(
        IllegalStateException.class,
        () ->
            single.apply(
                projectContext,
                one.id(),
                one.proposalSha256(),
                (path, before, after) -> fail("single must not bypass unknown batch")));
    var another = service().propose(owner(), request(op("UPDATE", "B.md", null, "old", "next")));
    assertThrows(
        RuntimeException.class,
        () -> service().apply(owner(), another.id(), another.proposalSha256()));
    assertEquals("old", Files.readString(root.resolve("A.md")));
    assertEquals("old", Files.readString(root.resolve("B.md")));
  }
}
