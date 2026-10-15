package dev.mikoto2000.rei.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.command.ProjectCommand;
import dev.mikoto2000.rei.core.command.ReiLineReaderFactory;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Size;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

@org.junit.jupiter.api.Timeout(15)
@Tag("integration")
class ProjectCommandHistoryTest {
  @TempDir Path root;
  DumbTerminal terminal;
  LineReaderImpl reader;
  final AtomicReference<ProjectContext> selected = new AtomicReference<>();

  @BeforeEach
  void setup() throws Exception {
    terminal =
        new DumbTerminal(
            "fixture",
            "xterm",
            new ByteArrayInputStream(new byte[0]),
            new ByteArrayOutputStream(),
            StandardCharsets.UTF_8);
    terminal.setSize(new Size(100, 30));
    reader =
        (LineReaderImpl)
            ReiLineReaderFactory.create(terminal, new CommandLine(CommandSpec.create())).reader();
    selected.set(
        new ProjectContext("00000000-0000-0000-0000-000000000001", "same-name", root.resolve("a")));
  }

  @AfterEach
  void cleanup() throws Exception {
    terminal.close();
  }

  String read(String input) {
    reader.runMacro(input + "\n");
    return reader.readLine("");
  }

  Path file(String id) {
    return root.resolve("command-history").resolve(id + ".history");
  }

  java.util.List<String> entries() {
    return reader.getHistory().iterator().hasNext()
        ? java.util.stream.StreamSupport.stream(reader.getHistory().spliterator(), false)
            .map(History.Entry::line)
            .toList()
        : java.util.List.of();
  }

  void selectB() {
    selected.set(
        new ProjectContext("00000000-0000-0000-0000-000000000002", "same-name", root.resolve("b")));
  }

  @Test
  void switchesFilesAndMemoryAndRestoresArrowAndSearchHistory() throws Exception {
    try (var history = new ProjectCommandHistory(() -> reader, selected::get, root)) {
      var projectA = selected.get();
      read("alpha command");
      read("/project cd b");
      var previous = reader.getHistory();
      selectB();
      history.refresh();
      assertThat(reader.getHistory()).isNotSameAs(previous);
      assertThat(entries()).isEmpty();
      assertThat(Files.readString(file("00000000-0000-0000-0000-000000000001")))
          .contains("alpha command", "/project cd b");
      read("beta command");
      assertThat(
              read(
                  org.jline.keymap.KeyMap.key(terminal, org.jline.utils.InfoCmp.Capability.key_up)))
          .isEqualTo("beta command");
      selected.set(projectA);
      history.refresh();
      assertThat(entries()).containsExactly("alpha command", "/project cd b");
      assertThat(
              read(
                  org.jline.keymap.KeyMap.key(terminal, org.jline.utils.InfoCmp.Capability.key_up)))
          .isEqualTo("/project cd b");
      assertThat(read("\u0012alpha\u001b")).isEqualTo("alpha command");
      assertThat(entries()).doesNotContain("beta command");
      assertThat(Files.readString(file("00000000-0000-0000-0000-000000000002")))
          .doesNotContain("alpha command");
    }
  }

  @Test
  void reloadsOnRestartAndKeepsLegacySharedHistoryUntouched() throws Exception {
    Files.writeString(root.resolve("history"), "old shared command\n");
    try (var history = new ProjectCommandHistory(() -> reader, selected::get, root)) {
      reader.unsetOpt(LineReader.Option.HISTORY_INCREMENTAL);
      read("persist on close");
    }
    assertThat(Files.readString(file("00000000-0000-0000-0000-000000000001")))
        .contains("persist on close");
    reader.setHistory(new org.jline.reader.impl.history.DefaultHistory());
    try (var history = new ProjectCommandHistory(() -> reader, selected::get, root)) {
      assertThat(entries()).containsExactly("persist on close");
      assertThat(
              read(
                  org.jline.keymap.KeyMap.key(terminal, org.jline.utils.InfoCmp.Capability.key_up)))
          .isEqualTo("persist on close");
    }
    assertThat(Files.readString(root.resolve("history"))).isEqualTo("old shared command\n");
  }

  @Test
  void unchangedProjectPreservesHistoryInstanceAndUnsavedEntries() throws Exception {
    try (var history = new ProjectCommandHistory(() -> reader, selected::get, root)) {
      reader.unsetOpt(LineReader.Option.HISTORY_INCREMENTAL);
      read("unsaved command");
      var current = reader.getHistory();
      history.refresh();
      assertThat(reader.getHistory()).isSameAs(current);
      assertThat(entries()).containsExactly("unsaved command");
    }
  }

  @Test
  void loadFailureKeepsPreviousFileAndMemoryAndCanBeRetried() throws Exception {
    try (var history = new ProjectCommandHistory(() -> reader, selected::get, root)) {
      read("alpha command");
      var current = reader.getHistory();
      Files.createDirectory(file("00000000-0000-0000-0000-000000000002"));
      selectB();
      assertThatThrownBy(history::refresh).isInstanceOf(java.io.IOException.class);
      assertThat(reader.getHistory()).isSameAs(current);
      assertThat(reader.getVariable(LineReader.HISTORY_FILE))
          .isEqualTo(file("00000000-0000-0000-0000-000000000001"));
      assertThat(entries()).containsExactly("alpha command");
      Files.delete(file("00000000-0000-0000-0000-000000000002"));
      history.refresh();
      assertThat(entries()).isEmpty();
    }
  }

  @Test
  void saveFailureDoesNotSwitchBackingFileOrMemory() throws Exception {
    try (var history = new ProjectCommandHistory(() -> reader, selected::get, root)) {
      reader.unsetOpt(LineReader.Option.HISTORY_INCREMENTAL);
      read("alpha command");
      var current = reader.getHistory();
      Files.createDirectory(file("00000000-0000-0000-0000-000000000001"));
      selectB();
      try {
        assertThatThrownBy(history::refresh).isInstanceOf(java.io.IOException.class);
        assertThat(reader.getHistory()).isSameAs(current);
        assertThat(reader.getVariable(LineReader.HISTORY_FILE))
            .isEqualTo(file("00000000-0000-0000-0000-000000000001"));
        assertThat(entries()).containsExactly("alpha command");
      } finally {
        Files.delete(file("00000000-0000-0000-0000-000000000001"));
      }
    }
  }

  @Test
  void followsActualCdFailedCdAndRemovalBackToStartup() throws Exception {
    Path startup = Files.createDirectory(root.resolve("startup"));
    Path other = Files.createDirectory(root.resolve("other"));
    var projects = new ProjectService(startup, root.resolve("projects.json"));
    var client = projects.newClient();
    var command =
        new CommandLine(CommandSpec.create().name("root"))
            .addSubcommand("cd", new ProjectCommand.CdCommand(projects))
            .addSubcommand("remove", new ProjectCommand.RemoveCommand(projects));
    ShellProjectCommands.configure(command, projects, client, null);
    try (var scope = client.open();
        var history = new ProjectCommandHistory(() -> reader, projects::currentContext, root)) {
      read("startup command");
      assertThat(command.execute("cd", other.toString())).isZero();
      history.refresh();
      assertThat(entries()).isEmpty();
      read("other command");
      assertThat(command.execute("cd", root.resolve("missing").toString())).isNotZero();
      history.refresh();
      assertThat(entries()).containsExactly("other command");
      assertThat(command.execute("remove", other.toString())).isZero();
      history.refresh();
      assertThat(entries()).containsExactly("startup command");
    }
  }
}
