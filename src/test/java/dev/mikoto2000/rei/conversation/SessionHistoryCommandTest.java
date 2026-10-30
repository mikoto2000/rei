package dev.mikoto2000.rei.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.mikoto2000.rei.application.session.SessionMetadata;
import dev.mikoto2000.rei.application.session.SessionQueryService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectClientTestSupport;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.ui.shell.HistoryCommand;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** 会話履歴コマンドの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class SessionHistoryCommandTest extends ProjectClientTestSupport {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temp;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Instant now = Instant.parse("2026-09-16T08:00:00Z");

  /** 検証に使う保存と検索の窓口。 */
  FileSessionRepository repository;

  /** 検証に使うやり取り一覧。 */
  ConversationTurnStore turns;

  /** 検証に使うプロジェクト。 */
  ProjectContext project;

  /** 検証に使う登録台帳。 */
  ProjectRegistry registry;

  /** 検証に使うプロジェクト一覧。 */
  ProjectService projects;

  /** 検証に使うコマンド。 */
  CommandLine command;

  /** 検証に使う出力。 */
  StringWriter output;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    registry = new ProjectRegistry(temp.resolve("projects.json"));
    project = registry.resolve(temp);
    repository = new FileSessionRepository(temp.resolve("sessions.json"));
    turns = new ConversationTurnStore(temp);
    projects = connect(new ProjectService(temp, registry));
    command =
        new CommandLine(
            new HistoryCommand(
                mock(HistoryShellService.class),
                new SessionQueryService(repository, turns),
                projects));
    output = new StringWriter();
    command.setOut(new PrintWriter(output));
    command.setErr(new PrintWriter(output));
  }

  /** 検証用に、会話履歴と受付記録の管理の操作を実行する。 */
  String run(String... args) {
    output.getBuffer().setLength(0);
    assertThat(command.execute(args)).isZero();
    return output.toString();
  }

  /** 検証用に、新たな対象を登録し、後から利用できるようにする。 */
  void add(String id, String title, Instant updated) {
    repository.accept(new SessionMetadata(id, project.id(), title, now, updated), () -> {});
  }

  /** 既定表示について、最新更新会話を利用することを確認する。 */
  @Test
  void defaultShowUsesLatestUpdatedSessionInSelectedProjectWithoutResumingIt() throws Exception {
    var other = registry.resolve(Files.createDirectory(temp.resolve("other")));
    repository.accept(
        new SessionMetadata("foreign", other.id(), "foreign title", now, now.plusSeconds(120)),
        () -> {});
    assertThat(run("show")).contains("No sessions.").doesNotContain("foreign");
    add("older", "old title", now);
    add("latest", "latest title", now.plusSeconds(60));
    projects.selectSession("older");
    var context = new AgentRunContext("running", "latest", temp);
    turns.start(context, "pending question", now);
    assertThat(run("show"))
        .contains("Session: latest", "pending question", "(No response recorded)")
        .doesNotContain("old title", "foreign title");
    assertThat(projects.currentSessionId()).isEqualTo("older");
    turns.finish(context, ConversationTurnStore.Status.COMPLETED, "finished answer");
    assertThat(run("show", "--limit", "1")).contains("finished answer");
    projects.cd(other.root().toString());
    assertThat(run("show")).contains("Session: foreign", "No turns.");
    projects.cd(project.root().toString());
    assertThat(run("show")).contains("Session: latest", "finished answer");
    assertThat(projects.currentSessionId()).isNull();
    assertThat(command.execute("show", "--cursor", "bad")).isNotZero();
  }

  /** 会話履歴と受付記録の管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void bareHistoryListsBoundedSessionsInUpdatedOrderWithProjectAndFullId() {
    assertThat(run()).contains("No sessions");
    add("older", "old title", now);
    add("newer", "new title", now.plusSeconds(60));
    var text = run();
    assertThat(text)
        .contains("newer", "older", project.name(), "old title", "new title", "UPDATED");
    assertThat(text.indexOf("new title")).isLessThan(text.indexOf("old title"));
    assertThat(run("--limit", "1")).contains("new title", "--cursor").doesNotContain("old title");
    assertThat(run("--project-id", "unknown")).contains("No sessions");
  }

  /** 会話履歴と受付記録の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void showDisplaysMetadataAndChronologicalTurnsWithNextPage() {
    add("session", "title", now);
    for (String id : new String[] {"b", "a"}) {
      var context = new AgentRunContext(id, "session", temp);
      turns.start(context, "question " + id, now);
      turns.finish(context, ConversationTurnStore.Status.COMPLETED, "answer " + id);
    }
    var text = run("show", "session");
    assertThat(text)
        .contains(
            "Session: session",
            "Project: " + project.name(),
            "Title: title",
            "Created:",
            "Updated:",
            "User:",
            "Rei:");
    assertThat(text.indexOf("question a")).isLessThan(text.indexOf("question b"));
    assertThat(run("show", "session", "--limit", "1"))
        .contains("question a", "--cursor")
        .doesNotContain("question b");
  }

  /** 長いユーザー本文と応答本文を末尾まで表示することを確認する。 */
  @Test
  void showDisplaysFullUserAndAssistantMessages() {
    add("session", "title", now);
    String question = "質問\n".repeat(1000) + "質問の末尾";
    String answer = "回答\n".repeat(1000) + "回答の末尾";
    var context = new AgentRunContext("run", "session", temp);
    turns.start(context, question, now);
    turns.finish(context, ConversationTurnStore.Status.COMPLETED, answer);
    assertThat(run("show", "session"))
        .contains(question, answer)
        .doesNotContain("(truncated)");
  }

  /** 会話履歴と受付記録の管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidOptionsAndUnknownSessionAreReportedAndMessagesAreSafeForTerminal() {
    for (String[] args :
        new String[][] {{"--limit", "0"}, {"--cursor", "bad"}, {"show", "missing"}}) {
      assertThat(command.execute(args)).isNotZero();
    }
    add("safe", "Authorization: Bearer SECRET\u001b[31m", now);
    assertThat(run()).contains("[REDACTED]").doesNotContain("SECRET", "\u001b");
    assertThat(command.execute("delete", "safe")).isNotZero();
    assertThat(command.execute("rename", "safe")).isNotZero();
  }
}
