package dev.mikoto2000.rei.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.*;
import java.nio.file.*;
import org.jline.reader.*;
import org.jline.terminal.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import dev.mikoto2000.rei.core.command.*;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

@Timeout(15)
class JLineCompletionInputTest {
  @TempDir Path root;
  String tab(String input) throws Exception {
    return read(input + "\t");
  }
  String read(String input) throws Exception {
    var command = new CommandLine(CommandSpec.create()).addSubcommand("project", new ProjectCommand());
    try (var terminal = TerminalBuilder.builder().system(false).type("xterm")
        .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()).build()) {
      terminal.setSize(new Size(100, 30));
      var reader = LineReaderBuilder.builder().terminal(terminal).parser(ReiLineReaderFactory.parser())
          .completer(new JLineCompletionAdapter(command, ReiLineReaderFactory.completionEngine(), () -> root)).build();
      reader.runMacro(input + "\n");
      return reader.readLine("");
    }
  }
  @ParameterizedTest
  @ValueSource(strings = {"f:\\project\\rei", "F:\\", "..\\rei\\", "\\\\server\\share\\rei",
      "\"F:\\My Documents\\rei\\\"", "'F:\\My Documents\\rei\\'"})
  void typedWindowsPathPreservesSingleBackslashes(String path) throws Exception {
    assertThat(read("/project cd " + path)).isEqualTo("/project cd " + path);
  }
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void tabCompletesWindowsPathAndPassesItToCd() throws Exception {
    Path directory = Files.createDirectories(root.resolve("My Documents/child"));
    String completed = tab("/project cd " + root + "\\My\tch");
    assertThat(completed).contains(directory.toString() + "\\");
    var service = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    var command = new CommandLine(CommandSpec.create())
        .addSubcommand("project", new CommandLine(CommandSpec.create())
            .addSubcommand("cd", new ProjectCommand.CdCommand(service)));
    assertThat(command.execute(new UserInputParser().parse(completed).arguments())).isZero();
    org.mockito.Mockito.verify(service).cd(directory.toString() + "\\");
  }
  @Test void tabCompletesCommandAndAppendsSpace() throws Exception {
    assertThat(tab("/pro")).isEqualTo("/project ");
  }
  @Test void tabQuotesSpaceAndKeepsDirectoryOpenForNextLevel() throws Exception {
    Files.createDirectory(root.resolve("My Documents"));
    var result = tab("/project add ./My");
    assertThat(new UserInputParser().split(result)).endsWith("./My Documents/");
    assertThat(result).doesNotEndWith(" ");
  }
  @Test void tabWithinExistingQuoteRoundTrips() throws Exception {
    Files.createDirectory(root.resolve("My Documents"));
    for (String quote : new String[]{"\"", "'"})
      assertThat(new UserInputParser().split(tab("/project add " + quote + "./My"))).endsWith("./My Documents/");
  }
  @Test void tabInMiddlePreservesFollowingArgument() throws Exception {
    assertThat(tab("/project c foo\u0002\u0002\u0002\u0002")).isEqualTo("/project cd foo");
  }
  @Test void repeatedTabDescendsThroughQuotedDirectory() throws Exception {
    Files.createDirectories(root.resolve("My Documents/child"));
    assertThat(new UserInputParser().split(tab("/project add ./My\tch"))).endsWith("./My Documents/child/");
  }
  @Test void homeCandidateCanActuallyBeInsertedByJline() throws Exception {
    // An injected home keeps this test independent of the developer's real files.
    Files.createDirectory(root.resolve("reports"));
    var command = new CommandLine(CommandSpec.create()).addSubcommand("project", new ProjectCommand());
    try (var terminal = TerminalBuilder.builder().system(false).type("xterm")
        .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()).build()) {
      terminal.setSize(new Size(100, 30));
      var reader = LineReaderBuilder.builder().terminal(terminal).parser(new ShellCompletionParser(root))
          .completer(new JLineCompletionAdapter(command, ReiLineReaderFactory.completionEngine(), () -> root)).build();
      reader.runMacro("/project add ~/rep\t\n");
      assertThat(new UserInputParser().split(reader.readLine("")))
          .endsWith(root.resolve("reports").toString().replace('\\', '/') + "/");
    }
  }
}
