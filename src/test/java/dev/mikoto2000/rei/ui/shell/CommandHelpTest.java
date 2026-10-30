package dev.mikoto2000.rei.ui.shell;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** 全コマンドの --help が、必須引数なしでも処理本体を実行せずに表示される。 */
class CommandHelpTest {
  @Test
  void everyShellCommandAndAliasSupportsHelpWithoutExecutingItsBody() {
    var objects = new ArrayList<Object>();
    var root = new RootCommand();
    var line =
        new CommandLine(
            root,
            new CommandLine.IFactory() {
              public <K> K create(Class<K> type) throws Exception {
                if (Iterable.class.isAssignableFrom(type))
                  return CommandLine.defaultFactory().create(type);
                var mock = org.mockito.Mockito.mock(type);
                objects.add(mock);
                return mock;
              }
            });
    root.configureCommands(line);
    var paths = new ArrayList<List<String>>();
    collect(line, List.of(), paths);
    assertTrue(paths.size() >= 190);
    for (var path : paths) {
      var output = new StringWriter();
      line.setOut(new PrintWriter(output));
      line.setErr(new PrintWriter(output));
      var arguments = new ArrayList<>(path);
      arguments.add("--help");
      assertEquals(0, line.execute(arguments.toArray(String[]::new)), path + "\n" + output);
      assertTrue(output.toString().contains("Usage:"), path.toString());
      assertTrue(output.toString().contains("--help"), path.toString());
    }
    org.mockito.Mockito.verifyNoInteractions(objects.toArray());
  }

  private static void collect(CommandLine line, List<String> path, List<List<String>> paths) {
    paths.add(path);
    assertTrue(line.getCommandSpec().usageMessage().description().length > 0, path.toString());
    for (var entry : line.getSubcommands().entrySet()) {
      var nested = new ArrayList<>(path);
      nested.add(entry.getKey());
      // Alias names are tested as independent public entry points.
      collect(entry.getValue(), nested, paths);
    }
  }
}
