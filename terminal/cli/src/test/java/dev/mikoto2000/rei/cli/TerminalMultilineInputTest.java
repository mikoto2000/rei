package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jline.keymap.KeyMap;
import org.jline.reader.Reference;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Size;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.InfoCmp.Capability;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 実際のキー入力で改行・編集・確定とパイプ入力の互換性を検証する。 */
@Timeout(10)
class TerminalMultilineInputTest {
  @TempDir Path directory;

  @ParameterizedTest
  @CsvSource({"LF,false", "LF,true", "CRLF,false", "CRLF,true", "CR,false", "CR,true"})
  void bracketedPasteWaitsForEnterAndSubmitsExactlyOneCompleteMessage(
      String lineEnding, boolean trailingNewline) throws Exception {
    String separator =
        switch (lineEnding) {
          case "CRLF" -> "\r\n";
          case "CR" -> "\r";
          default -> "\n";
        };
    String pasted =
        "一行目"
            + separator
            + separator
            + "/help"
            + separator
            + "最終行"
            + (trailingNewline ? separator : "");
    String expected = "一行目\n\n/help\n最終行" + (trailingNewline ? "\n" : "");
    var submitted = new CopyOnWriteArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try (exchange) {
            String response =
                switch (exchange.getRequestURI().getPath()) {
                  case "/api/v1/projects" -> "[{\"id\":\"p\",\"name\":\"Fixture\"}]";
                  case "/api/v1/sessions" -> "{\"items\":[]}";
                  case "/api/v1/cli/catalog" -> "{\"commands\":[]}";
                  case "/api/v1/chat" -> {
                    submitted.add(
                        BackendClient.JSON
                            .readTree(exchange.getRequestBody())
                            .path("message")
                            .asText());
                    yield "{\"sessionId\":\"s\",\"runId\":\"r\"}";
                  }
                  default -> "{}";
                };
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          }
        });
    server.start();
    try (var backend =
            new BackendClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "fixture");
        var terminal = terminal("xterm", "\033[200~" + pasted + "\033[201~\033[99~\r/exit\r");
        var client = new TerminalClient(backend, directory, true, terminal)) {
      client.command("/project select p");
      var reader = reader(client);
      var pasteObserved = new AtomicBoolean();
      reader
          .getWidgets()
          .put(
              "inspect-paste",
              () -> {
                // This key is consumed AFTER the paste end marker, BEFORE the user's Enter.
                assertEquals(expected, reader.getBuffer().toString());
                assertTrue(submitted.isEmpty(), "Paste alone must not send a chat request");
                pasteObserved.set(true);
                return true;
              });
      reader
          .getKeyMaps()
          .get(org.jline.reader.LineReader.MAIN)
          .bind(new Reference("inspect-paste"), "\033[99~");
      client.run();
      assertTrue(pasteObserved.get(), "The paste must remain editable until Enter");
      assertEquals(List.of(expected), submitted);
    } finally {
      server.stop(0);
    }
  }

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
    return terminal(type, "");
  }

  private DumbTerminal terminal(String type, String input) throws Exception {
    var terminal =
        new DumbTerminal(
            "fixture",
            type,
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
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
