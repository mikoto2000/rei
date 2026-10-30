package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.mikoto2000.rei.application.cli.CliCatalog;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jline.reader.Candidate;
import org.jline.reader.Parser;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Size;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** 軽量 CLI 固有の操作名とカーソル位置を含む実際の Tab 入力を検証する。 */
@Timeout(15)
class TerminalCompletionTest {
  @TempDir Path directory;

  @Test
  void localActionsAreAvailableWithoutCatalogAndDoNotLeakIntoArguments() throws Exception {
    try (var backend = new BackendClient(URI.create("http://127.0.0.1:1"), "fixture");
        var terminal = terminal();
        var client = new TerminalClient(backend, directory, true, terminal)) {
      var expected =
          Map.of(
              "/project", List.of("select", "register"),
              "/session", List.of("select"),
              "/history", List.of("off", "on"),
              "/input-history", List.of("off", "on"),
              "/run-mode", List.of("exclusive", "read-only", "conversation"));
      for (var entry : expected.entrySet()) {
        assertTrue(
            client
                .completionValues(List.of(entry.getKey(), ""), 1, "")
                .containsAll(entry.getValue()),
            entry.getKey());
        for (var action : entry.getValue()) {
          assertEquals(
              List.of(action), client.completionValues(List.of(entry.getKey(), action), 1, action));
          assertTrue(client.completionValues(List.of(entry.getKey(), action, ""), 2, "").isEmpty());
        }
      }
    }
  }

  @Test
  void tabUsesOnlyThePrefixBeforeCursorForCommandsSubcommandsOptionsAndPaths() throws Exception {
    var option =
        new CliCatalog.Option(
            List.of("--output"),
            "",
            null,
            "1",
            false,
            List.of(),
            "dev.mikoto2000.rei.core.completion.PathCompletionCandidates$Any");
    var command =
        new CliCatalog.Command(
            "fixture",
            List.of(),
            "",
            List.of(option),
            List.of(),
            List.of(
                new CliCatalog.Command("resume", List.of(), "", List.of(), List.of(), List.of())));
    var bytes = BackendClient.JSON.writeValueAsBytes(new CliCatalog(List.of(command)));
    var requests = new java.util.concurrent.atomic.AtomicInteger();
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/cli/catalog",
        exchange -> {
          requests.incrementAndGet();
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try (var backend =
            new BackendClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "fixture");
        var terminal = terminal();
        var client = new TerminalClient(backend, directory, true, terminal)) {
      client.refreshCatalog();
      var field = TerminalClient.class.getDeclaredField("reader");
      field.setAccessible(true);
      var reader = (LineReaderImpl) field.get(client);
      for (var sample :
          List.of(
              List.of("/projXYZ", "/project"),
              List.of("/fixture reXYZ", "resume"),
              List.of("/fixture --outXYZ", "--output"))) {
        var candidates = new ArrayList<Candidate>();
        var line = sample.getFirst();
        reader
            .getCompleter()
            .complete(
                reader,
                reader.getParser().parse(line, line.length() - 3, Parser.ParseContext.COMPLETE),
                candidates);
        assertEquals(
            List.of(sample.get(1)), candidates.stream().map(Candidate::value).toList(), line);
      }
      assertEquals("/project ", tab(reader, "/projXYZ"));
      assertEquals("/fixture resume ", tab(reader, "/fixture reXYZ"));
      assertEquals("/fixture --output ", tab(reader, "/fixture --outXYZ"));
      assertEquals("/project register ", tab(reader, "/project regXYZ"));
      String withArgument = "/fixture reXYZ keep";
      assertEquals("/fixture resume keep", tab(reader, withArgument, "/fixture re".length()));
      Path report = Files.createFile(directory.resolve("report.txt"));
      String path = report.toString();
      String prefix = path.substring(0, path.length() - "port.txt".length());
      assertEquals(
          "/fixture --output " + path + " ", tab(reader, "/fixture --output " + prefix + "XYZ"));
      Path spacedReport =
          Files.createFile(
              Files.createDirectory(directory.resolve("My Documents")).resolve("report.txt"));
      String quoted =
          "/fixture --output \"" + spacedReport.toString().replace("report.txt", "reXYZ") + "\"";
      String completed = tab(reader, quoted, quoted.length() - 4);
      assertEquals(
          List.of("/fixture", "--output", spacedReport.toString()),
          List.of(new dev.mikoto2000.rei.core.command.UserInputParser().split(completed)));
      assertEquals(1, requests.get(), "Tab must use the snapshot without HTTP requests");
    } finally {
      server.stop(0);
    }
  }

  private static String tab(LineReaderImpl reader, String line) {
    return tab(reader, line, line.length() - 3);
  }

  private static String tab(LineReaderImpl reader, String line, int cursor) {
    reader.runMacro(line + "\u0002".repeat(line.length() - cursor) + "\t\n");
    return reader.readLine("");
  }

  private static DumbTerminal terminal() throws Exception {
    var terminal =
        new DumbTerminal(
            "fixture",
            "xterm",
            new ByteArrayInputStream(new byte[0]),
            new ByteArrayOutputStream(),
            java.nio.charset.StandardCharsets.UTF_8);
    terminal.setSize(new Size(120, 30));
    return terminal;
  }
}
