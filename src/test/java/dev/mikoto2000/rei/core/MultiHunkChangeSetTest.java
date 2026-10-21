package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.mikoto2000.rei.core.project.ProjectContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 複数Hunk変更集合の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class MultiHunkChangeSetTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使う所属プロジェクトの識別子。 */
  final String projectId = UUID.randomUUID().toString();

  /** 検証で使用するプロジェクトを、指定された条件に合わせて用意する。 */
  ProjectContext project() {
    return new ProjectContext(projectId, "test", root);
  }

  /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
  TextChangeSetService service() {
    return new TextChangeSetService(
        new TextChangeSetRepository(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:sqlite:" + root.resolve("changes.db"))));
  }

  /** 検証で使用する要求を、指定された条件に合わせて用意する。 */
  TextChangeSetService.Request request(
      TextChangeSetService service, List<TextChangeSetService.Edit> edits) throws Exception {
    return new TextChangeSetService.Request(
        "a.txt", null, null, service.readBase(project(), "a.txt").sha256(), edits);
  }

  /** 元の形式のバージョンを利用することを確認する。 */
  @Test
  void multipleHunksUseTheOriginalVersionAndExplicitApplyIsOneShot() throws Exception {
    Files.writeString(root.resolve("a.txt"), "abcd");
    var service = service();
    var proposal =
        service.propose(
            project(),
            request(
                service,
                List.of(
                    new TextChangeSetService.Edit("ab", "cd"),
                    new TextChangeSetService.Edit("cd", "ef"))));
    assertEquals("abcd", Files.readString(root.resolve("a.txt")));
    assertTrue(proposal.diff().contains("+cdef"));
    assertEquals(
        "APPLIED",
        service
            .apply(
                project(),
                proposal.id(),
                proposal.proposalSha256(),
                TextDocumentTransaction::writeSingle)
            .status());
    assertEquals("cdef", Files.readString(root.resolve("a.txt")));
    assertEquals(
        "APPLIED",
        service()
            .apply(
                project(), proposal.id(), proposal.proposalSha256(), (p, o, n) -> fail("no replay"))
            .status());
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void ambiguousMissingOverlappingAndStaleEditsNeverModifyTheFile() throws Exception {
    Files.writeString(root.resolve("a.txt"), "abcd abcd");
    var service = service();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.propose(
                project(), request(service, List.of(new TextChangeSetService.Edit("ab", "x")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.propose(
                project(),
                request(service, List.of(new TextChangeSetService.Edit("missing", "x")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.propose(
                project(), request(service, List.of(new TextChangeSetService.Edit("", "x")))));
    Files.writeString(root.resolve("a.txt"), "abcd");
    var overlapping =
        request(
            service,
            List.of(
                new TextChangeSetService.Edit("abc", "x"),
                new TextChangeSetService.Edit("bcd", "y")));
    assertThrows(IllegalArgumentException.class, () -> service.propose(project(), overlapping));
    var stale = request(service, List.of(new TextChangeSetService.Edit("ab", "x")));
    Files.writeString(root.resolve("a.txt"), "human");
    assertThrows(IllegalArgumentException.class, () -> service.propose(project(), stale));
    assertEquals("human", Files.readString(root.resolve("a.txt")));
  }

  /** 共通の実行基盤で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void deletionMultilineJapaneseBomAndLineEndingsArePreserved() throws Exception {
    for (var newline : List.of("\n", "\r\n")) {
      var text = "\ufeff日本語" + newline + "remove" + newline + "last";
      Files.writeString(root.resolve("a.txt"), text);
      var service = service();
      var proposal =
          service.propose(
              project(),
              request(
                  service,
                  List.of(
                      new TextChangeSetService.Edit("remove" + newline, ""),
                      new TextChangeSetService.Edit("日本語", "変更"))));
      service.apply(
          project(),
          proposal.id(),
          proposal.proposalSha256(),
          TextDocumentTransaction::writeSingle);
      assertArrayEquals(
          ("\ufeff変更" + newline + "last").getBytes(java.nio.charset.StandardCharsets.UTF_8),
          Files.readAllBytes(root.resolve("a.txt")));
    }
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void discardAndLegacyFullReplacementRemainCompatible() throws Exception {
    Files.writeString(root.resolve("a.txt"), "old");
    var service = service();
    var legacy =
        service.propose(project(), new TextChangeSetService.Request("a.txt", "old", "legacy"));
    assertEquals(
        "DISCARDED", service.discard(project(), legacy.id(), legacy.proposalSha256()).status());
    var partial =
        service.propose(
            project(), request(service, List.of(new TextChangeSetService.Edit("old", "new"))));
    assertEquals(
        "DISCARDED", service.discard(project(), partial.id(), partial.proposalSha256()).status());
    assertThrows(
        IllegalStateException.class,
        () ->
            service.apply(
                project(),
                partial.id(),
                partial.proposalSha256(),
                TextDocumentTransaction::writeSingle));
    assertEquals("old", Files.readString(root.resolve("a.txt")));
    assertEquals(
        Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.LOCAL_WRITE),
        dev.mikoto2000.rei.core.policy.ToolPermissionPolicy.intrinsicCapabilities(
            "proposeTextChangeSet"));
  }

  /** 対象を保持することを確認する。 */
  @Test
  void failedAtomicReplaceAndLastMomentExternalChangePreserveTarget() throws Exception {
    var file = root.resolve("a.txt");
    Files.writeString(file, "old");
    assertThrows(
        java.io.IOException.class,
        () ->
            TextDocumentTransaction.writeSingle(
                file,
                "old",
                "new",
                (staged, target) -> {
                  throw new java.io.IOException("injected move failure");
                }));
    assertEquals("old", Files.readString(file));
    var service = service();
    var proposal =
        service.propose(
            project(), request(service, List.of(new TextChangeSetService.Edit("old", "new"))));
    assertThrows(
        java.io.IOException.class,
        () ->
            service.apply(
                project(),
                proposal.id(),
                proposal.proposalSha256(),
                (path, before, after) -> {
                  Files.writeString(path, "human");
                  TextDocumentTransaction.writeSingle(path, before, after);
                }));
    assertEquals("human", Files.readString(file));
    assertEquals("FAILED_UNCERTAIN", service.inspect(project(), proposal.id()).status());
    assertThrows(
        IllegalStateException.class,
        () ->
            service()
                .apply(
                    project(),
                    proposal.id(),
                    proposal.proposalSha256(),
                    (p, o, n) -> fail("no replay")));
  }

  /** 共通の実行基盤で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void versionsAndInputModesAreStrictAndHunkCountIsBounded() throws Exception {
    Files.writeString(root.resolve("a.txt"), "old");
    var service = service();
    var version = service.readBase(project(), "a.txt").sha256();
    var edits = List.of(new TextChangeSetService.Edit("old", "new"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.propose(
                project(),
                new TextChangeSetService.Request("a.txt", "old", "new", version, edits)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.propose(
                project(), new TextChangeSetService.Request("a.txt", null, null, null, edits)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.propose(
                project(),
                new TextChangeSetService.Request(
                    "a.txt", null, null, version, Collections.nCopies(65, edits.getFirst()))));
  }

  /** 共通の実行基盤の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void atomicWritingPreservesPermissionsAndPolicyDenialStillPrecedesCallback() throws Exception {
    var file = root.resolve("a.txt");
    Files.writeString(file, "old");
    var acl = Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class);
    var posix =
        Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class);
    var originalAcl = acl == null ? null : acl.getAcl();
    var originalPermissions = posix == null ? null : posix.readAttributes().permissions();
    TextDocumentTransaction.writeSingle(file, "old", "new");
    if (originalAcl != null)
      assertEquals(
          originalAcl,
          Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class)
              .getAcl());
    if (originalPermissions != null)
      assertEquals(originalPermissions, Files.getPosixFilePermissions(file));
    var policy =
        new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(
            new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(
                true,
                null,
                Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.LOCAL_WRITE),
                null));
    var guard =
        new dev.mikoto2000.rei.core.policy.ToolPermissionGuard(
            policy,
            new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
            new dev.mikoto2000.rei.event.InMemoryAgentEventBus());
    assertThrows(
        dev.mikoto2000.rei.core.policy.ToolPermissionException.class,
        () ->
            guard.check(
                "proposeTextChangeSet", "{\"baseVersion\":\"version\",\"edits\":[]}", null));
    assertThrows(
        dev.mikoto2000.rei.core.policy.ToolPermissionException.class,
        () -> guard.check("applyTextChangeSet", null));
    assertEquals("new", Files.readString(file));
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void partialDiffShowsTheChangedRegionWithoutDumpingUnchangedFile() throws Exception {
    var text = new StringBuilder();
    for (int i = 0; i < 500; i++)
      text.append(i == 350 ? "int timeout = 30;\n" : "unchanged line " + i + "\n");
    Files.writeString(root.resolve("a.txt"), text);
    var service = service();
    var proposal =
        service.propose(
            project(),
            request(
                service,
                List.of(new TextChangeSetService.Edit("int timeout = 30;", "int timeout = 60;"))));
    assertTrue(proposal.diff().contains("-int timeout = 30;"));
    assertTrue(proposal.diff().contains("+int timeout = 60;"));
    assertFalse(proposal.diff().contains("unchanged line 0\n"));
    assertFalse(proposal.diff().contains("unchanged line 499\n"));
    assertEquals(
        text.toString().replace("int timeout = 30;", "int timeout = 60;"),
        new String(
            service.exportProposal(project(), proposal.id()),
            java.nio.charset.StandardCharsets.UTF_8));
  }

  /** 既存のツール完了時の処理について、部分的な結果JSON変換を受け付けられることを確認する。 */
  @Test
  void existingToolCallbackAcceptsPartialJsonWithoutWholeFileFields() throws Exception {
    Files.writeString(root.resolve("a.txt"), "old");
    var service = service();
    var tools = new Tools();
    tools.setTextChangeSets(service);
    var callback =
        Arrays.stream(
                org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                    .toolObjects(tools)
                    .build()
                    .getToolCallbacks())
            .filter(tool -> tool.getToolDefinition().name().equals("proposeTextChangeSet"))
            .findFirst()
            .orElseThrow();
    var input =
        "{\"request\":{\"path\":\"a.txt\",\"baseVersion\":\""
            + service.readBase(project(), "a.txt").sha256()
            + "\",\"edits\":[{\"oldText\":\"old\",\"newText\":\"new\"}]}}";
    try (var scope =
        dev.mikoto2000.rei.core.chat.AgentRunScope.open(
            new dev.mikoto2000.rei.core.chat.AgentRunContext("run", "session", root, projectId))) {
      var result = new com.fasterxml.jackson.databind.ObjectMapper().readTree(callback.call(input));
      assertEquals("PROPOSED", result.get("status").asText());
      assertTrue(result.get("diff").asText().contains("+new"));
    }
    assertEquals("old", Files.readString(root.resolve("a.txt")));
  }
}
