package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** テキスト文書変更集合ツールの結果と状態の契約を検証するテスト。 */
class TextDocumentChangeSetToolTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 延長済み単一ファイルサービスおよび既存のツール一覧について、取得済み所有者を利用することを確認する。 */
  @Test
  void extendedSingleFileServiceAndExistingToolsUseCapturedOwnerAndOneCommittedEvent()
      throws Exception {
    var owner = new AgentRunContext("run", "session", root, UUID.randomUUID().toString());
    var repository =
        new TextChangeSetRepository(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:sqlite:" + root.resolve("changes.db")));
    var single = new TextChangeSetService(repository);
    single.setDocuments(
        new TextDocumentChangeSetService(
            repository, Clock.systemUTC(), TextDocumentTransaction::replace));
    var factory = org.mockito.Mockito.mock(dev.mikoto2000.rei.event.AgentEventFactory.class);
    var publisher = org.mockito.Mockito.mock(dev.mikoto2000.rei.event.AgentEventPublisher.class);
    var tools =
        new Tools(
            null,
            new dev.mikoto2000.rei.core.service.SystemShellService(),
            null,
            Clock.systemUTC(),
            new dev.mikoto2000.rei.core.working.WorkingSet(),
            factory,
            publisher);
    tools.setTextChangeSets(single);
    Files.writeString(root.resolve("A.md"), "old");
    try (var scope = AgentRunScope.open(owner)) {
      var proposal =
          tools.proposeTextDocumentChangeSet(
              new TextDocumentChangeSetService.Request(
                  List.of(
                      new TextDocumentChangeSetService.Operation(
                          "UPDATE", "A.md", null, "old", "new"))));
      assertEquals("PROPOSED", tools.inspectTextDocumentChangeSet(proposal.id()).status());
      assertTrue(
          tools
              .applyTextDocumentChangeSet(proposal.id(), proposal.proposalSha256())
              .currentMatches());
      assertTrue(
          tools
              .applyTextDocumentChangeSet(proposal.id(), proposal.proposalSha256())
              .currentMatches());
    }
    var policy = new ToolPermissionPolicy(new ToolPermissionProperties(true, null, null, null));
    assertEquals(
        Set.of(ActionCapability.READ), policy.capabilities("inspectTextDocumentChangeSet"));
    assertEquals(
        Set.of(ActionCapability.LOCAL_WRITE), policy.capabilities("proposeTextDocumentChangeSet"));
    assertEquals(
        Set.of(ActionCapability.LOCAL_WRITE, ActionCapability.DESTRUCTIVE),
        policy.capabilities("applyTextDocumentChangeSet"));
    org.mockito.Mockito.verify(factory, org.mockito.Mockito.times(1))
        .fileModified(root.resolve("A.md").toString(), null, null);
  }

  /** 共通の実行基盤で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void humanRecoveryCommandRoutesExactInstructionThroughExistingExclusiveQueue() {
    var conversations =
        org.mockito.Mockito.mock(
            dev.mikoto2000.rei.application.session.ShellConversationService.class);
    var cli =
        new picocli.CommandLine(new dev.mikoto2000.rei.core.command.DocumentCommand(conversations));
    String id = UUID.randomUUID().toString(), sha = "a".repeat(64);
    assertEquals(0, cli.execute("rollback", id, sha));
    org.mockito.Mockito.verify(conversations).submit("/document rollback " + id + " " + sha);
    assertEquals(0, cli.execute("reconcile-single", id, sha));
    org.mockito.Mockito.verify(conversations)
        .submit("/document reconcile-single " + id + " " + sha);
    assertEquals(2, cli.execute("auto", id, sha));
    org.mockito.Mockito.verifyNoMoreInteractions(conversations);
    assertTrue(
        Arrays.asList(
                dev.mikoto2000.rei.ui.shell.RootCommand.class
                    .getAnnotation(picocli.CommandLine.Command.class)
                    .subcommands())
            .contains(dev.mikoto2000.rei.core.command.DocumentCommand.class));
  }
}
