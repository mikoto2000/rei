package dev.mikoto2000.rei.ui.shell;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.conversation.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.command.UserInputParser;
import picocli.CommandLine;
import static org.assertj.core.api.Assertions.*;

class SessionManagementTest {
  @TempDir Path temp;
  @Test void showListNewAndSwitchUseSharedLedgerWithoutSubmittingMessages() throws Exception {
    var repository = new FileSessionRepository(temp.resolve("sessions.json"));
    var projects = new ProjectService(temp, new ProjectRegistry(temp.resolve("projects.json")), repository);
    var shell = new ShellConversationService(projects, new SessionLifecycle(repository, Clock.systemUTC()),
        (c,p) -> { throw new AssertionError("management must not dispatch chat"); });
    var query = new SessionQueryService(repository, ConversationTurnStore.inMemory());
    var command = new CommandLine(new SessionCommand(shell, query, projects), new CommandLine.IFactory() {
      public <K> K create(Class<K> type) throws Exception {
        if (type == NewConversationCommand.class) return type.cast(new NewConversationCommand(shell));
        if (type == ResumeConversationCommand.class) return type.cast(new ResumeConversationCommand(shell));
        return CommandLine.defaultFactory().create(type);
      }
    });
    var out = new StringWriter(); var err = new StringWriter();
    command.setOut(new PrintWriter(out, true)); command.setErr(new PrintWriter(err, true));
    try (var scope = projects.newClient().open()) {
      assertThat(command.execute()).isZero();
      assertThat(out.toString()).contains("No active session.");
      out.getBuffer().setLength(0);
      assertThat(command.execute("list")).isZero();
      assertThat(out.toString()).contains("No sessions.");
      assertThat(command.execute("new", "Dify investigation")).isZero();
      String id = shell.currentSessionId();
      assertThat(query.getSession(id).title()).isEqualTo("Dify investigation");
      out.getBuffer().setLength(0);
      assertThat(command.execute()).isZero(); String implicit = out.toString();
      out.getBuffer().setLength(0);
      assertThat(command.execute("show")).isZero();
      assertThat(out.toString()).isEqualTo(implicit).contains(id, "Dify investigation", "createdAt:", "updatedAt:");
      assertThat(command.execute("new")).isZero();
      assertThat(query.getSession(shell.currentSessionId()).title()).isEqualTo("New session");
      assertThat(shell.currentSessionId()).isNotEqualTo(id);
      assertThat(command.execute("switch", id)).isZero();
      assertThat(shell.currentSessionId()).isEqualTo(id);
      var project = projects.currentContext();
      repository.accept(new SessionMetadata("recent", project.id(), "Latest", Instant.EPOCH, Instant.parse("2099-01-01T00:00:00Z")), () -> {});
      repository.accept(new SessionMetadata("foreign", "other", "Other project", Instant.EPOCH, Instant.EPOCH), () -> {});
      out.getBuffer().setLength(0);
      assertThat(command.execute("list")).isZero();
      assertThat(out.toString()).contains("* " + id).doesNotContain("foreign");
      assertThat(out.toString().indexOf("recent")).isLessThan(out.toString().indexOf(id));
      assertThat(command.execute("switch", "unknown-session")).isEqualTo(2);
      assertThat(err.toString()).contains("Session not found: unknown-session");
      assertThat(command.execute("switch", "foreign")).isEqualTo(2);
      assertThat(shell.currentSessionId()).isEqualTo(id);
      assertThat(projects.currentContext()).isEqualTo(project);
      assertThat(command.execute("switch")).isEqualTo(2);
      assertThat(command.execute("foo")).isEqualTo(2);
      assertThat(err.toString()).contains("Usage:", "switch", "list", "show");
    }
  }
  @Test void parserRoutesAllSessionFormsAsCommands() {
    var parser = new UserInputParser();
    for (var input : List.of("/session", "/session list", "/session new", "/session new \"a title\"",
        "/session show", "/session switch id", "/session switch", "/session foo")) {
      assertThat(parser.parse(input).kind()).isEqualTo(UserInputParser.Kind.SLASH_COMMAND);
    }
    assertThat(parser.parse("/session new \"a title\"").arguments()).containsExactly("session", "new", "a title");
  }
}
