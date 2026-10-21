package dev.mikoto2000.rei.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.application.session.ShellConversationService;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.chat.ResponseStyle;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** 会話動作モードコマンドの結果と状態の契約を検証するテスト。 */
class ConversationModeCommandTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** ターミナルでの対話で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void statusDoesNotCreateSessionAndCommandsPersistSelectedSessionOnly() {
    var projects = new ProjectService(root, new ProjectRegistry(root.resolve("projects.json")));
    var shell =
        new ShellConversationService(
            projects,
            new SessionLifecycle(
                new FileSessionRepository(root.resolve("sessions.json")), Clock.systemUTC()),
            (c, p) -> {});
    var out = new StringWriter();
    var cli = new CommandLine(new ConversationModeCommand(shell));
    cli.setOut(new PrintWriter(out));
    try (var scope = projects.newClient().open()) {
      assertThat(cli.execute("status")).isZero();
      assertThat(shell.currentSessionId()).isNull();
      assertThat(out.toString()).contains("auto");
      assertThat(cli.execute("conversation", "--voice-only")).isZero();
      assertThat(shell.currentMetadata().responseStyle()).isEqualTo(ResponseStyle.CONVERSATION);
      assertThat(shell.currentMetadata().voiceOnly()).isTrue();
      var first = shell.currentSessionId();
      shell.newConversation();
      assertThat(cli.execute("status")).isZero();
      assertThat(shell.currentMetadata().responseStyle()).isEqualTo(ResponseStyle.AUTO);
      shell.resume(first);
      assertThat(cli.execute("conversation")).isZero();
      assertThat(shell.currentMetadata().voiceOnly()).isFalse();
      assertThat(cli.execute("normal")).isZero();
      assertThat(shell.currentMetadata().responseStyle()).isEqualTo(ResponseStyle.NORMAL);
      assertThat(cli.execute("auto")).isZero();
      assertThat(shell.currentMetadata().responseStyle()).isEqualTo(ResponseStyle.AUTO);
      var before = shell.currentMetadata();
      assertThat(cli.execute("conversation", "--unknown")).isEqualTo(2);
      assertThat(shell.currentMetadata()).isEqualTo(before);
    }
  }

  /** ターミナルでの対話で、音声の取得・認識・入力の受け渡しが、検証条件に従うことを確認する。 */
  @Test
  void commandIsRegisteredAndHelpDescribesVoiceOnly() {
    assertThat(RootCommand.class.getAnnotation(CommandLine.Command.class).subcommands())
        .contains(ConversationModeCommand.class);
    assertThat(
            new CommandLine(new ConversationModeCommand())
                .getSubcommands()
                .get("conversation")
                .getUsageMessage())
        .contains("--voice-only");
  }
}
