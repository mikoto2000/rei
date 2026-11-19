package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.jline.keymap.KeyMap;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Size;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.InfoCmp.Capability;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 実際のキー入力で改行・編集・確定とパイプ入力の互換性を検証する。 */
@Timeout(10)
class TerminalMultilineInputTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"emacs", "viins"})
  void ctrlJInsertsNewlinesAtCursorAndEnterAcceptsTheWholeMessage(String keyMap) throws Exception {
    try (var backend = new BackendClient(URI.create("http://127.0.0.1:1"), "fixture");
        var terminal = terminal("xterm");
        var client = new TerminalClient(backend, directory, true, terminal)) {
      var reader = reader(client);
      reader.setKeyMap(keyMap);
      // Move left into the first line, insert two newlines, then edit the last line.
      String left = KeyMap.key(terminal, Capability.key_left);
      reader.runMacro("前半後半" + left.repeat(2) + "\n\n追記x\177\r次の入力\r");
      assertEquals("前半\n\n追記後半", reader.readLine(""));
      assertEquals("次の入力", reader.readLine(""));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"dumb", "dumb-color"})
  void lineFeedStillAcceptsPipedInput(String type) throws Exception {
    try (var backend = new BackendClient(URI.create("http://127.0.0.1:1"), "fixture");
        var terminal = terminal(type);
        var client = new TerminalClient(backend, directory, true, terminal)) {
      var reader = reader(client);
      reader.runMacro("first\nsecond\n");
      assertEquals("first", reader.readLine(""));
      assertEquals("second", reader.readLine(""));
    }
  }

  private DumbTerminal terminal(String type) throws Exception {
    var terminal =
        new DumbTerminal(
            "fixture",
            type,
            new ByteArrayInputStream(new byte[0]),
            new ByteArrayOutputStream(),
            StandardCharsets.UTF_8);
    terminal.setSize(new Size(120, 30));
    return terminal;
  }

  private LineReaderImpl reader(TerminalClient client) throws Exception {
    var field = TerminalClient.class.getDeclaredField("reader");
    field.setAccessible(true);
    return (LineReaderImpl) field.get(client);
  }
}
