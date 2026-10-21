package dev.mikoto2000.rei.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.command.UserInputParser;
import dev.mikoto2000.rei.core.command.UserInputService;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.ui.shell.HistoryCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import picocli.CommandLine;

/** 履歴シェルコマンドの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class HistoryShellCommandTest extends dev.mikoto2000.rei.core.project.ProjectClientTestSupport {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temp;

  /** 検証に使うprior。 */
  String prior;

  /** 検証に使う登録台帳。 */
  ProjectRegistry registry;

  /** 検証に使うa、b。 */
  ProjectContext a, b;

  /** 検証に使うプロジェクト一覧。 */
  ProjectService projects;

  /** 検証に使うログ一覧。 */
  ConversationLogStore logs;

  /** 検証に使う検索。 */
  ConversationHistorySearchService search;

  /** 検証に使うコマンド。 */
  CommandLine command;

  /** 検証に使う出力。 */
  java.io.StringWriter output;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() throws Exception {
    prior = System.getProperty("rei.data-dir");
    System.setProperty("rei.data-dir", temp.resolve("data").toString());
    registry = new ProjectRegistry(temp.resolve("data/projects.json"));
    a = registry.resolve(Files.createDirectory(temp.resolve("Alpha")));
    b = registry.resolve(Files.createDirectory(temp.resolve("MaCa Editor")));
    projects = connect(new ProjectService(a.root(), registry));
    logs = spy(new ConversationLogStore());
    var ds = new SQLiteDataSource();
    ds.setUrl("jdbc:sqlite:" + temp.resolve("unused.db"));
    search = spy(new ConversationHistorySearchService(ds, logs));
    command =
        new CommandLine(
            new HistoryCommand(
                new HistoryShellService(projects, logs, search),
                new dev.mikoto2000.rei.application.session.SessionQueryService(
                    new FileSessionRepository(temp.resolve("sessions.json")),
                    new ConversationTurnStore(temp)),
                projects));
    output = new java.io.StringWriter();
    command.setOut(new java.io.PrintWriter(output));
    command.setErr(new java.io.PrintWriter(output));
  }

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void cleanup() {
    if (prior == null) System.clearProperty("rei.data-dir");
    else System.setProperty("rei.data-dir", prior);
  }

  /** 検証用に、会話履歴と受付記録の管理の操作を実行する。 */
  String run(String... args) {
    output.getBuffer().setLength(0);
    assertThat(command.execute(args)).isZero();
    return output.toString();
  }

  /** 検証用に、新たな対象を登録し、後から利用できるようにする。 */
  void add(ProjectContext project, String id, String role, String text) {
    logs.append(project.conversationId(id), role, text);
  }

  /** 明示的な旧形式表示について、会話を保持することを確認する。 */
  @Test
  void explicitLegacyShowPreservesConversationAndLastLimits() {
    for (int i = 0; i < 65; i++) add(a, "chat:main", "user", "message-" + i);
    add(b, "chat:main", "user", "foreign");
    String bare = run("show", "chat:main");
    assertThat(bare)
        .isEqualTo(run("show", "chat:main"))
        .contains("Project: Alpha", "Conversation: chat:main", "message-15", "message-64")
        .doesNotContain("message-14", "foreign", "Usage:");
    assertThat(run("show", "--last", "2"))
        .contains("message-63", "message-64")
        .doesNotContain("message-62");
    assertThat(run("show", "--all")).contains("message-0", "message-64");
    verify(logs, never()).readProject(anyString());
    verify(logs, never()).readAll();
  }

  /** 一覧について、プロジェクト付随情報を利用することを確認する。 */
  @Test
  void listUsesProjectMetadataAndSupportsPagingAndEmptyResults() {
    add(a, "chat:main", "user", "one");
    add(a, "chat:main", "assistant", "two");
    add(b, "chat:foreign", "user", "three");
    assertThat(run("list"))
        .contains("Alpha", "chat:main", "2", "UPDATED", "MESSAGES")
        .doesNotContain("chat:foreign");
    assertThat(run("list", "--project", b.id()))
        .contains("chat:foreign")
        .doesNotContain("chat:main");
    assertThat(run("list", "--limit", "1", "--offset", "1")).contains("No conversations");
  }

  /** 会話履歴と受付記録の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void explicitProjectAndConversationAreReadWithoutSwitchingShell() {
    add(b, "chat:test", "tool", "tool output");
    add(b, "chat:main", "assistant", "default B");
    assertThat(run("show", "chat:test", "--project", "MaCa Editor"))
        .contains("tool", "tool output", "chat:test");
    assertThat(run("show", "--project", b.id())).contains("default B");
    assertThat(projects.currentContext().id()).isEqualTo(a.id());
  }

  /** 会話履歴と受付記録の管理で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void displaysAllPersistedRolesAndInterventionsWithRedactionAndTruncation() {
    for (String role : List.of("system", "user", "assistant", "tool", "user"))
      add(a, "chat:main", role, role + " message");
    add(a, "chat:main", "tool", "Authorization: Bearer SECRET_VALUE\n" + "payload".repeat(600));
    assertThat(run("show", "chat:main"))
        .contains("] system", "] user", "] assistant", "] tool", "[REDACTED]", "(truncated)")
        .doesNotContain("SECRET_VALUE");
  }

  /** 検索について、既存の権限の範囲を利用することを確認する。 */
  @Test
  void searchUsesExistingScopesAndDisplaysProvenance() {
    add(a, "chat:main", "user", "Working Set local");
    add(b, "chat:test", "user", "Working Set foreign");
    assertThat(run("search", "Working", "Set"))
        .contains("Alpha", "MaCa Editor", "chat:test", "foreign");
    assertThat(run("search", "--current", "Working Set"))
        .contains("local")
        .doesNotContain("foreign");
    assertThat(run("search", "--all", "--limit", "1", "Working Set"))
        .contains("1.")
        .doesNotContain("2.");
    verify(search)
        .searchForShell(
            argThat(
                r ->
                    r.retrievalScope() == HistorySearchScope.CURRENT_PROJECT_PREFERRED
                        && r.preferredProjectId().equals(a.id())));
    verify(search)
        .searchForShell(
            argThat(r -> r.retrievalScope() == HistorySearchScope.CURRENT_PROJECT_ONLY));
    verify(search)
        .searchForShell(
            argThat(r -> r.retrievalScope() == HistorySearchScope.ALL_PROJECTS && r.limit() == 1));
  }

  /** 会話履歴と受付記録の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void controlsUseShellBWhileRunScopeAExistsAndDoNotTouchActiveRuns() {
    add(a, "chat:main", "user", "A only");
    add(b, "chat:main", "user", "B needle");
    var tasks = new ArrayList<Runnable>();
    var router =
        new ConversationInputRouter(tasks::add, (c, p, q) -> assertThat(q.drain()).isEmpty());
    router.submit(a.root(), a.conversationId("chat:main"), "A work");
    router.submit(b.root(), b.conversationId("chat:main"), "B work");
    var before = router.activeRuns();
    projects.cd(b.root().toString());
    try (var scope = AgentRunScope.open(new AgentRunContext("run-a", a, "chat:main"))) {
      for (String text : List.of("/history show chat:main", "/history search --current needle")) {
        var input = new UserInputService(new UserInputParser()).interpret(text);
        assertThat(input.kind()).isEqualTo(UserInputService.Kind.COMMAND);
        assertThat(run(Arrays.copyOfRange(input.arguments(), 1, input.arguments().length)))
            .contains("MaCa Editor")
            .doesNotContain("A only");
      }
    }
    assertThat(router.activeRuns()).isEqualTo(before);
    tasks.forEach(Runnable::run);
    assertThat(router.activeRuns()).isEmpty();
  }

  /** 会話履歴と受付記録の管理で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void handlesUsageAndResolutionErrorsWithoutStackTraces() throws Exception {
    for (String[] args :
        List.of(
            new String[] {"search"},
            new String[] {"search", "--limit", "abc", "q"},
            new String[] {"search", "--limit", "0", "q"},
            new String[] {"show", "--last", "-1"},
            new String[] {"show", "--last", "abc"},
            new String[] {"show", "--all", "--last", "5"},
            new String[] {"search", "--current", "--all", "q"},
            new String[] {"show", "chat:missing"},
            new String[] {"list", "--project", "missing"})) {
      output.getBuffer().setLength(0);
      assertThat(command.execute(args)).isNotZero();
      assertThat(output.toString()).doesNotContain("Exception", "\tat ");
    }
    registry.resolve(Files.createDirectories(temp.resolve("duplicate/Alpha")));
    output.getBuffer().setLength(0);
    assertThat(command.execute("list", "--project", "Alpha")).isNotZero();
    assertThat(output.toString()).contains("Ambiguous project");
    assertThat(run("--help")).contains("show", "search", "list");
  }

  /** 会話履歴と受付記録の管理で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void searchStreamsStoresAndRedactsBeforeCreatingSnippetsWithoutPublishingEvents() {
    add(a, "chat:main", "user", "token=" + "SECRET".repeat(200) + " needle");
    doThrow(new AssertionError("Must not load all history")).when(logs).readProject(anyString());
    var publisher = mock(dev.mikoto2000.rei.event.AgentEventPublisher.class);
    search.setHistoryEvents(
        new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()), publisher);
    assertThat(run("search", "--current", "needle"))
        .contains("needle", "[REDACTED]")
        .doesNotContain("SECRET");
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(publisher);
  }
}
