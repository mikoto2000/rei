package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.jline.keymap.KeyMap;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Size;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.InfoCmp.Capability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Shell と同じプロジェクト別の入力履歴を、実際の履歴キーで検証する。 */
@Timeout(15)
class TerminalInputHistoryTest {
  @TempDir Path directory;

  @Test
  void arrowKeysFollowProjectSwitchesFailedSelectionAndRestart() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String body =
              switch (exchange.getRequestURI().getPath()) {
                case "/api/v1/projects" ->
                    "[{\"id\":\"alpha\",\"name\":\"same\"},{\"id\":\"beta\",\"name\":\"same\"}]";
                case "/api/v1/cli/catalog" -> "{\"commands\":[]}";
                case "/api/v1/sessions" -> "{\"items\":[]}";
                default -> "{\"enabled\":false}";
              };
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try (var backend =
        new BackendClient(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "fixture")) {
      try (var client = client(backend)) {
        client.command("/project select alpha");
        read(client, "alpha input");
        client.command("/project select beta");
        assertEquals("", read(client, key(client, Capability.key_up)));
        read(client, "beta input");
        assertThrows(
            IllegalArgumentException.class, () -> client.command("/project select missing"));
        assertEquals("beta input", read(client, key(client, Capability.key_up)));
        client.command("/project select alpha");
        assertEquals("alpha input", read(client, key(client, Capability.key_up)));
        assertEquals(
            "", read(client, key(client, Capability.key_up) + key(client, Capability.key_down)));
        client.command("/project select alpha");
        assertEquals("alpha input", read(client, key(client, Capability.key_up)));
      }
      try (var client = client(backend)) {
        client.command("/project select beta");
        assertEquals("beta input", read(client, key(client, Capability.key_up)));
        client.command("/project select alpha");
        assertEquals("alpha input", read(client, key(client, Capability.key_up)));
      }
    } finally {
      server.stop(0);
    }
  }

  private TerminalClient client(BackendClient backend) throws Exception {
    var terminal =
        new DumbTerminal(
            "fixture",
            "xterm",
            new ByteArrayInputStream(new byte[0]),
            new ByteArrayOutputStream(),
            StandardCharsets.UTF_8);
    terminal.setSize(new Size(120, 30));
    return new TerminalClient(backend, directory, false, terminal);
  }

  private LineReaderImpl reader(TerminalClient client) throws Exception {
    var field = TerminalClient.class.getDeclaredField("reader");
    field.setAccessible(true);
    return (LineReaderImpl) field.get(client);
  }

  private String key(TerminalClient client, Capability capability) throws Exception {
    return KeyMap.key(reader(client).getTerminal(), capability);
  }

  private String read(TerminalClient client, String input) throws Exception {
    var reader = reader(client);
    reader.runMacro(input + "\n");
    String line = reader.readLine("");
    var field = TerminalClient.class.getDeclaredField("history");
    field.setAccessible(true);
    ((ClientHistory) field.get(client)).record(line);
    return line;
  }
}
