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

class ConversationModeCommandTest {
  @TempDir Path root;

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
