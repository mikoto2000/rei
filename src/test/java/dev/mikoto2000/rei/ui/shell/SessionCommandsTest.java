package dev.mikoto2000.rei.ui.shell;

import java.nio.file.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.project.*;
import static org.assertj.core.api.Assertions.*;

class SessionCommandsTest {
  @TempDir Path temp;
  @Test void rootExposesSessionGroupWithoutTopLevelAliases() {
    var root = new picocli.CommandLine(new RootCommand());
    assertThat(root.getSubcommands()).containsKey("session").doesNotContainKeys("new", "resume");
    var session = root.getSubcommands().get("session");
    assertThat(session.getSubcommands()).containsOnlyKeys("new", "resume");
    assertThat(root.execute("session", "--help")).isZero();
    assertThat(root.execute("session")).isZero();
    assertThatThrownBy(() -> root.parseArgs("new")).isInstanceOf(picocli.CommandLine.UnmatchedArgumentException.class);
    assertThatThrownBy(() -> root.parseArgs("resume", "id")).isInstanceOf(picocli.CommandLine.UnmatchedArgumentException.class);
  }
  @Test void explicitNewAndResumePreserveProjectAndRejectUnknownOrForeignSessions() throws Exception {
    var repository = new FileSessionRepository(temp.resolve("sessions.json"));
    var projects = new ProjectService(temp, new ProjectRegistry(temp.resolve("projects.json")), repository);
    var shell = new ShellConversationService(projects, new SessionLifecycle(repository, Clock.systemUTC()), (c,p)->{});
    var command = new picocli.CommandLine(new SessionCommand(), new picocli.CommandLine.IFactory() {
      public <K> K create(Class<K> type) throws Exception {
        if (type == NewConversationCommand.class) return type.cast(new NewConversationCommand(shell));
        if (type == ResumeConversationCommand.class) return type.cast(new ResumeConversationCommand(shell));
        return picocli.CommandLine.defaultFactory().create(type);
      }
    });
    try (var scope = projects.newClient().open()) {
      var first = shell.submit("first");
      assertThat(command.execute("new")).isZero();
      assertThat(shell.currentSessionId()).isNull();
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
      assertThat(repository.findById(first.conversationId()).orElseThrow().projectId()).isEqualTo(first.projectId());
      assertThat(other.conversationId()).isNotEqualTo(first.conversationId());
      var cd = new picocli.CommandLine(new dev.mikoto2000.rei.core.command.ProjectCommand.CdCommand(projects));
      assertThat(cd.execute(temp.toString())).isZero();
      assertThat(shell.currentSessionId()).isEqualTo(first.conversationId());
      assertThat(shell.submit("continued after cd").conversationId()).isEqualTo(first.conversationId());
      assertThat(command.execute("resume", second.conversationId())).isZero();
      assertThat(cd.execute(temp.toString())).isZero();
      assertThat(shell.currentSessionId()).isEqualTo(second.conversationId());
      assertThat(command.execute("new")).isZero();
      assertThat(cd.execute(temp.toString())).isZero();
      assertThat(shell.currentSessionId()).isNull();
      assertThat(cd.execute(temp.resolve("other").toString())).isZero();
      assertThat(shell.currentSessionId()).isEqualTo(other.conversationId());
    }
  }
}
