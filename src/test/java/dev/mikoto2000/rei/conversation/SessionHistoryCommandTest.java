package dev.mikoto2000.rei.conversation;

import java.nio.file.*;
import java.time.*;
import java.io.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.ui.shell.HistoryCommand;
import picocli.CommandLine;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionHistoryCommandTest {
  @TempDir Path temp;
  final Instant now = Instant.parse("2026-09-16T08:00:00Z");
  FileSessionRepository repository;
  ConversationTurnStore turns;
  ProjectContext project;
  CommandLine command;
  StringWriter output;
  @BeforeEach void setup() {
    var registry = new ProjectRegistry(temp.resolve("projects.json"));
    project = registry.resolve(temp);
    repository = new FileSessionRepository(temp.resolve("sessions.json"));
    turns = new ConversationTurnStore(temp);
    command = new CommandLine(new HistoryCommand(mock(HistoryShellService.class),
        new SessionQueryService(repository, turns), new ProjectService(temp, registry)));
    output = new StringWriter(); command.setOut(new PrintWriter(output)); command.setErr(new PrintWriter(output));
  }
  String run(String... args) {
    output.getBuffer().setLength(0); assertThat(command.execute(args)).isZero(); return output.toString();
  }
  void add(String id, String title, Instant updated) {
    repository.accept(new SessionMetadata(id, project.id(), title, now, updated), () -> {});
  }
  @Test void bareHistoryListsBoundedSessionsInUpdatedOrderWithProjectAndFullId() {
    assertThat(run()).contains("No sessions");
    add("older", "old title", now); add("newer", "new title", now.plusSeconds(60));
    var text = run();
    assertThat(text).contains("newer", "older", project.name(), "old title", "new title", "UPDATED");
    assertThat(text.indexOf("new title")).isLessThan(text.indexOf("old title"));
    assertThat(run("--limit", "1")).contains("new title", "--cursor").doesNotContain("old title");
    assertThat(run("--project-id", "unknown")).contains("No sessions");
  }
  @Test void showDisplaysMetadataAndChronologicalTurnsWithNextPage() {
    add("session", "title", now);
    for (String id : new String[]{"b", "a"}) {
      var context = new AgentRunContext(id, "session", temp);
      turns.start(context, "question " + id, now);
      turns.finish(context, ConversationTurnStore.Status.COMPLETED, "answer " + id);
    }
    var text = run("show", "session");
    assertThat(text).contains("Session: session", "Project: " + project.name(), "Title: title", "Created:", "Updated:", "User:", "Rei:");
    assertThat(text.indexOf("question a")).isLessThan(text.indexOf("question b"));
    assertThat(run("show", "session", "--limit", "1")).contains("question a", "--cursor").doesNotContain("question b");
  }
  @Test void invalidOptionsAndUnknownSessionAreReportedAndMessagesAreSafeForTerminal() {
    for (String[] args : new String[][]{{"--limit","0"}, {"--cursor","bad"}, {"show","missing"}}) {
      assertThat(command.execute(args)).isNotZero();
    }
    add("safe", "Authorization: Bearer SECRET\u001b[31m", now);
    assertThat(run()).contains("[REDACTED]").doesNotContain("SECRET", "\u001b");
    assertThat(command.execute("delete", "safe")).isNotZero();
    assertThat(command.execute("rename", "safe")).isNotZero();
  }
}
