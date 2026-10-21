package dev.mikoto2000.rei.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.application.session.ShellConversationService;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 会話コマンド一覧の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class SessionCommandsTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temp;

  /** ターミナルでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void rootExposesSessionGroupWithoutTopLevelAliases() {
    var root = new picocli.CommandLine(new RootCommand());
    assertThat(root.getSubcommands())
        .containsKeys("session", "resume", "checkpoint")
        .doesNotContainKey("new");
    var session = root.getSubcommands().get("session");
    assertThat(session.getSubcommands())
        .containsOnlyKeys("new", "resume", "switch", "show", "list", "end");
    assertThat(root.execute("session", "--help")).isZero();
    assertThat(root.execute("session")).isZero();
    assertThatThrownBy(() -> root.parseArgs("new"))
        .isInstanceOf(picocli.CommandLine.UnmatchedArgumentException.class);
    assertThat(root.parseArgs("resume", "id").subcommand().commandSpec().userObject())
        .isInstanceOf(dev.mikoto2000.rei.checkpoint.ResumeCommand.class);
  }

  /** 明示的な新規および再開について、プロジェクトを保持することを確認する。 */
  @Test
  void explicitNewAndResumePreserveProjectAndRejectUnknownOrForeignSessions() throws Exception {
    var repository = new FileSessionRepository(temp.resolve("sessions.json"));
    var projects =
        new ProjectService(temp, new ProjectRegistry(temp.resolve("projects.json")), repository);
    var shell =
        new ShellConversationService(
            projects, new SessionLifecycle(repository, Clock.systemUTC()), (c, p) -> {});
    var command =
        new picocli.CommandLine(
            new SessionCommand(),
            new picocli.CommandLine.IFactory() {
              /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
              public <K> K create(Class<K> type) throws Exception {
                if (type == NewConversationCommand.class)
                  return type.cast(new NewConversationCommand(shell));
                if (type == ResumeConversationCommand.class)
                  return type.cast(new ResumeConversationCommand(shell));
                return picocli.CommandLine.defaultFactory().create(type);
              }
            });
    try (var scope = projects.newClient().open()) {
      var first = shell.submit("first");
      assertThat(command.execute("new")).isZero();
      assertThat(shell.currentSessionId()).isNotNull().isNotEqualTo(first.conversationId());
      var second = shell.submit("second");
      assertThat(second.conversationId()).isNotEqualTo(first.conversationId());
      assertThat(command.execute("resume", first.conversationId())).isZero();
      assertThat(shell.submit("third").conversationId()).isEqualTo(first.conversationId());
      assertThat(command.execute("resume", "unknown")).isEqualTo(2);
      assertThat(shell.currentSessionId()).isEqualTo(first.conversationId());
      projects.cd(Files.createDirectory(temp.resolve("other")).toString());
      assertThat(shell.currentSessionId()).isNull();
      assertThat(command.execute("resume", first.conversationId())).isEqualTo(2);
      var other = shell.submit("other project");
      assertThat(other.projectId()).isNotEqualTo(first.projectId());
      assertThat(repository.findById(first.conversationId()).orElseThrow().projectId())
          .isEqualTo(first.projectId());
      assertThat(other.conversationId()).isNotEqualTo(first.conversationId());
      var cd =
          new picocli.CommandLine(
              new dev.mikoto2000.rei.core.command.ProjectCommand.CdCommand(projects));
      assertThat(cd.execute(temp.toString())).isZero();
      assertThat(shell.currentSessionId()).isEqualTo(first.conversationId());
      assertThat(shell.submit("continued after cd").conversationId())
          .isEqualTo(first.conversationId());
      assertThat(command.execute("resume", second.conversationId())).isZero();
      assertThat(cd.execute(temp.toString())).isZero();
      assertThat(shell.currentSessionId()).isEqualTo(second.conversationId());
      assertThat(command.execute("new")).isZero();
      var fresh = shell.currentSessionId();
      assertThat(cd.execute(temp.toString())).isZero();
      assertThat(shell.currentSessionId()).isEqualTo(fresh);
      assertThat(cd.execute(temp.resolve("other").toString())).isZero();
      assertThat(shell.currentSessionId()).isEqualTo(other.conversationId());
    }
  }
}
