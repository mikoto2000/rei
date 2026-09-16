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
  @Test void explicitNewAndResumePreserveProjectAndRejectUnknownOrForeignSessions() throws Exception {
    var projects = new ProjectService(temp, new ProjectRegistry(temp.resolve("projects.json")));
    var repository = new FileSessionRepository(temp.resolve("sessions.json"));
    var shell = new ShellConversationService(projects, new SessionLifecycle(repository, Clock.systemUTC()), (c,p)->{});
    var newCommand = new picocli.CommandLine(new NewConversationCommand(shell));
    var resume = new picocli.CommandLine(new ResumeConversationCommand(shell));
    try (var scope = projects.newClient().open()) {
      var first = shell.submit("first");
      assertThat(newCommand.execute()).isZero();
      assertThat(shell.currentSessionId()).isNull();
      var second = shell.submit("second");
      assertThat(second.conversationId()).isNotEqualTo(first.conversationId());
      assertThat(resume.execute(first.conversationId())).isZero();
      assertThat(shell.submit("third").conversationId()).isEqualTo(first.conversationId());
      assertThat(resume.execute("unknown")).isEqualTo(2);
      assertThat(shell.currentSessionId()).isEqualTo(first.conversationId());
      projects.cd(Files.createDirectory(temp.resolve("other")).toString());
      assertThat(shell.currentSessionId()).isNull();
      assertThat(resume.execute(first.conversationId())).isEqualTo(2);
      var other = shell.submit("other project");
      assertThat(other.projectId()).isNotEqualTo(first.projectId());
      assertThat(repository.findById(first.conversationId()).orElseThrow().projectId()).isEqualTo(first.projectId());
      assertThat(other.conversationId()).isNotEqualTo(first.conversationId());
    }
  }
}
