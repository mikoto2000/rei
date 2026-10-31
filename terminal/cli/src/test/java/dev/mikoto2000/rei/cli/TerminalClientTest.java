package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 終端クライアントの結果と状態の契約を検証するテスト。 */
class TerminalClientTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  @Test
  void sseDisplaysFileChangesAndGlobalShutdownWithoutJson() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var requests = new java.util.concurrent.atomic.AtomicInteger();
    var output = new ByteArrayOutputStream();
    server.createContext(
        "/api/v1/runs/run/events",
        exchange -> {
          requests.incrementAndGet();
          String body =
              "id: 1\nevent: file.modified\ndata: {\"runId\":\"run\",\"projectId\":\"p\",\"timestamp\":\"2026-10-30T00:00:00Z\",\"payload\":{\"path\":\"src/main.java\",\"addedLines\":3,\"removedLines\":1}}\n\n"
                  + "id: 2\nevent: application.shutdown.started\ndata: {\"runId\":null,\"timestamp\":\"2026-10-30T00:00:01Z\",\"payload\":{}}\n\n";
          var bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try (var backend =
            new BackendClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "fixture");
        var client =
            new TerminalClient(
                backend,
                directory,
                true,
                TerminalBuilder.builder()
                    .system(false)
                    .dumb(true)
                    .streams(new ByteArrayInputStream(new byte[0]), output)
                    .build())) {
      client.command("/watch run");
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      while (!output.toString(java.nio.charset.StandardCharsets.UTF_8).contains("グレースフルシャットダウン")
          && System.nanoTime() < deadline) Thread.sleep(10);
      String text = output.toString(java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(text.contains("[file] modified main.java (+3/-1)"));
      assertTrue(text.contains("[application] グレースフルシャットダウンを開始します。"));
      assertFalse(text.contains("Event Run identity mismatch"));
      assertFalse(text.contains("\"path\""));
      assertEquals(1, requests.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void promptShowsSelectedProjectAndGlobalCountAndClearsStaleCounts() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var response = new java.util.concurrent.atomic.AtomicReference<>("{\"count\":3}");
    var requests = new java.util.concurrent.atomic.AtomicInteger();
    server.createContext(
        "/api/v1/runs/active",
        exchange -> {
          requests.incrementAndGet();
          var bytes = response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try (var backend =
            new BackendClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "fixture");
        var client =
            new TerminalClient(
                backend,
                directory,
                true,
                TerminalBuilder.builder()
                    .system(false)
                    .dumb(true)
                    .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                    .build())) {
      client.refreshRunStatus();
      assertEquals(
          "[none] [3 running]\n12:34 unknown> ",
          client.buildPrompt(java.time.LocalTime.of(12, 34)));
      client.buildPrompt(java.time.LocalTime.NOON);
      assertEquals(1, requests.get(), "Prompt rendering never performs HTTP");
      response.set("{\"count\":3,\"progress\":[\"全体進捗: 1 / 3 工程完了\",\"✓ 調査\",\"○ 実装\",\"○ 検証\"]}");
      client.refreshRunStatus();
      assertEquals(
          "全体進捗: 1 / 3 工程完了\n✓ 調査\n○ 実装\n○ 検証\n[none] [3 running]\n12:34 unknown> ",
          client.buildPrompt(java.time.LocalTime.of(12, 34), true));
      assertEquals(
          "[none] [3 running]\n12:34 unknown> ",
          client.buildPrompt(java.time.LocalTime.of(12, 34), false));
      response.set("{\"count\":0}");
      client.refreshRunStatus();
      assertTrue(client.buildPrompt(java.time.LocalTime.NOON).contains("[0 running]"));
      assertFalse(client.buildPrompt(java.time.LocalTime.NOON).contains("全体進捗"));
      response.set("{\"text\":\"old backend\"}");
      client.refreshRunStatus();
      assertTrue(client.buildPrompt(java.time.LocalTime.NOON).contains("[? running]"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void userFramesUseShellLayoutForMultilineAndHideProjectAndShellCommands() throws Exception {
    var output = new ByteArrayOutputStream();
    try (var backend = new BackendClient(URI.create("http://127.0.0.1:1"), "fixture");
        var client =
            new TerminalClient(
                backend,
                directory,
                true,
                TerminalBuilder.builder()
                    .system(false)
                    .dumb(true)
                    .streams(new ByteArrayInputStream(new byte[0]), output)
                    .build())) {
      client.printUserInput("こんにちは\n続き");
      String text = output.toString(java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(text.contains("┌ User ("));
      assertTrue(text.contains("こんにちは"));
      assertTrue(text.contains("続き"));
      assertTrue(text.contains("└"));
      output.reset();
      client.printUserInput("/project cd C:\\work\\");
      client.printUserInput("/sh");
      assertEquals("", output.toString());
      client.printUserInput("/chat 質問");
      assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8).contains("/chat 質問"));
    }
  }

  /** 軽量CLIでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void commandTokenizationOmitsEmptyQuotedArgumentsLikeExistingShell() {
    assertEquals(java.util.List.of("/models"), TerminalClient.commandWords("/models \"\" "));
    assertEquals(
        java.util.List.of("/chat", "hello world", "C:\\work\\file"),
        TerminalClient.commandWords("/chat \"hello world\" C:\\work\\file"));
  }

  /** 助言について、読み取り改訂番号を利用することを確認する。 */
  @Test
  void coachingUsesTheReadRevisionAndDoesNotWriteBeforeReading() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var calls = new java.util.ArrayList<String>();
    var written =
        new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
    server.createContext(
        "/api/v1/activity",
        exchange -> {
          String response;
          if (exchange.getRequestMethod().equals("GET")) {
            calls.add("read");
            response = "{\"revision\":7}";
          } else {
            calls.add("write");
            written.set(BackendClient.JSON.readTree(exchange.getRequestBody()));
            response = "{\"text\":\"enabled\"}";
          }
          var bytes = response.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try (var backend =
            new BackendClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "fixture");
        var client =
            new TerminalClient(
                backend,
                directory,
                true,
                TerminalBuilder.builder()
                    .system(false)
                    .dumb(true)
                    .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                    .build())) {
      client.command("/activity coaching on");
      assertEquals(java.util.List.of("read", "write"), calls);
      assertEquals(7, written.get().path("expectedRevision").asLong());
      assertTrue(written.get().path("enabled").asBoolean());
    } finally {
      server.stop(0);
    }
  }

  /** シェルテキストを利用することを確認する。 */
  @Test
  void modelControlsUseShellTextAndCacheNamesForCompletion() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var output = new ByteArrayOutputStream();
    server.createContext(
        "/api/v1/models",
        exchange -> {
          String response =
              exchange.getRequestURI().getPath().endsWith("/current")
                  ? "{\"model\":\"chosen\"}"
                  : "{\"models\":[\"chosen\",\"other\"],\"currentModel\":\"chosen\"}";
          var bytes = response.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try (var backend =
            new BackendClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "fixture");
        var client =
            new TerminalClient(
                backend,
                directory,
                true,
                TerminalBuilder.builder()
                    .system(false)
                    .dumb(true)
                    .streams(new ByteArrayInputStream(new byte[0]), output)
                    .build())) {
      client.command("/models");
      assertTrue(output.toString().contains("* chosen"));
      assertFalse(output.toString().contains("currentModel"));
      assertEquals(
          "[none] [? running]\n20:34 chosen> ", client.buildPrompt(java.time.LocalTime.of(20, 34)));
      assertEquals(
          java.util.List.of("other"),
          client.completionValues(java.util.List.of("/model", "o"), 1, "o"));
      output.reset();
      client.command("/model chosen");
      assertTrue(output.toString().contains("current model: chosen"));
      assertEquals(
          "[none] [? running]\n09:05 chosen> ", client.buildPrompt(java.time.LocalTime.of(9, 5)));
    } finally {
      server.stop(0);
    }
  }

  /** 軽量CLIでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void startupWithoutProjectOptionSelectsTheClientWorkingDirectory() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var registered = new java.util.concurrent.atomic.AtomicReference<String>();
    String path =
        BackendClient.JSON.writeValueAsString(Path.of("").toAbsolutePath().normalize().toString());
    server.createContext(
        "/api/v1/projects",
        exchange -> {
          String response;
          if (exchange.getRequestMethod().equals("POST")) {
            registered.set(
                BackendClient.JSON.readTree(exchange.getRequestBody()).path("path").asText());
            response = "{\"id\":\"cwd\",\"name\":\"Current\",\"path\":" + path + "}";
          } else response = "[{\"id\":\"cwd\",\"name\":\"Current\",\"path\":" + path + "}]";
          var bytes = response.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.createContext(
        "/api/v1/sessions",
        exchange -> {
          var bytes = "{\"items\":[]}".getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try (var backend =
            new BackendClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "fixture");
        var client =
            new TerminalClient(
                backend,
                directory,
                true,
                TerminalBuilder.builder()
                    .system(false)
                    .dumb(true)
                    .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                    .build())) {
      client.selectInitialProject(null);
      assertEquals("cwd", client.projectId());
      assertTrue(client.buildPrompt(java.time.LocalTime.NOON).startsWith("[Current] ["));
      assertEquals(Path.of("").toAbsolutePath().normalize().toString(), registered.get());
    } finally {
      server.stop(0);
    }
  }

  /** タブ完了について、利用可能な項目キャッシュを利用することを確認する。 */
  @Test
  void tabCompletionUsesTheCatalogCacheWithoutAnyAdditionalHttpRequest() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var requests = new java.util.concurrent.atomic.AtomicInteger();
    var command =
        new dev.mikoto2000.rei.application.cli.CliCatalog.Command(
            "session",
            java.util.List.of(),
            "Sessions",
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(
                new dev.mikoto2000.rei.application.cli.CliCatalog.Command(
                    "switch",
                    java.util.List.of("resume"),
                    "Select",
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of())));
    var bytes =
        BackendClient.JSON.writeValueAsBytes(
            new dev.mikoto2000.rei.application.cli.CliCatalog(java.util.List.of(command)));
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
        var client =
            new TerminalClient(
                backend,
                directory,
                true,
                TerminalBuilder.builder()
                    .system(false)
                    .dumb(true)
                    .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                    .build())) {
      client.refreshCatalog();
      assertEquals(1, requests.get());
      var readerField = TerminalClient.class.getDeclaredField("reader");
      readerField.setAccessible(true);
      var input = (org.jline.reader.LineReader) readerField.get(client);
      var candidates = new java.util.ArrayList<org.jline.reader.Candidate>();
      ((org.jline.reader.impl.LineReaderImpl) input)
          .getCompleter()
          .complete(
              input,
              input.getParser().parse("/", 1, org.jline.reader.Parser.ParseContext.COMPLETE),
              candidates);
      var sessionCandidate =
          candidates.stream()
              .filter(value -> value.value().equals("/session"))
              .findFirst()
              .orElseThrow();
      assertEquals("command", sessionCandidate.group());
      assertEquals("Sessions", sessionCandidate.descr());
      candidates.clear();
      ((org.jline.reader.impl.LineReaderImpl) input)
          .getCompleter()
          .complete(
              input,
              input
                  .getParser()
                  .parse("/session r", 10, org.jline.reader.Parser.ParseContext.COMPLETE),
              candidates);
      var alias =
          candidates.stream()
              .filter(value -> value.value().equals("resume"))
              .findFirst()
              .orElseThrow();
      assertEquals("command", alias.group());
      assertEquals("Select", alias.descr());
      assertTrue(input.isSet(org.jline.reader.LineReader.Option.BRACKETED_PASTE));
      if (java.io.File.separatorChar == '\\')
        assertTrue(input.isSet(org.jline.reader.LineReader.Option.CASE_INSENSITIVE));
      for (int index = 0; index < 100; index++)
        assertEquals(
            java.util.List.of("resume"),
            client.completionValues(java.util.List.of("/session", "r"), 1, "r"));
      client.refreshCatalog();
      assertEquals(1, requests.get());
    } finally {
      server.stop(0);
    }
  }

  /** 会話一覧について、各対象ページを読み取ることを確認する。 */
  @Test
  void sessionListReadsEveryPageAndUsesTheShellDisplay() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var pages = new java.util.concurrent.atomic.AtomicInteger();
    var output = new ByteArrayOutputStream();
    server.createContext(
        "/api/v1/projects",
        exchange -> {
          var bytes = "[{\"id\":\"one\",\"name\":\"One\"}]".getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.createContext(
        "/api/v1/sessions",
        exchange -> {
          String query = exchange.getRequestURI().getQuery();
          String response = "{\"items\":[]}";
          if (query != null && query.contains("limit=100")) {
            pages.incrementAndGet();
            response =
                query.contains("cursor=next")
                    ? "{\"items\":[{\"sessionId\":\"second\",\"title\":\"Second\"}]}"
                    : "{\"items\":[{\"sessionId\":\"first\",\"title\":\"First\"}],\"nextCursor\":\"next\"}";
          }
          var bytes = response.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try (var backend =
            new BackendClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "fixture");
        var client =
            new TerminalClient(
                backend,
                directory,
                true,
                TerminalBuilder.builder()
                    .system(false)
                    .dumb(true)
                    .streams(new ByteArrayInputStream(new byte[0]), output)
                    .build())) {
      client.command("/project select one");
      pages.set(0);
      client.command("/session list");
      assertEquals(2, pages.get());
      assertTrue(output.toString().contains("Sessions:"));
      assertTrue(output.toString().contains("first  First"));
      assertTrue(output.toString().contains("second  Second"));
      assertThrows(IllegalArgumentException.class, () -> client.command("/session list extra"));
    } finally {
      server.stop(0);
    }
  }

  /** クライアント会話を保持することを確認する。 */
  @Test
  void changingToTheSameProjectPreservesTheClientSession() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String path = BackendClient.JSON.writeValueAsString(directory.toString());
    server.createContext(
        "/api/v1/projects",
        exchange -> {
          var response =
              exchange.getRequestMethod().equals("POST")
                  ? "{\"id\":\"one\",\"name\":\"One\",\"path\":" + path + "}"
                  : "[{\"id\":\"one\",\"name\":\"One\",\"path\":" + path + "}]";
          var bytes = response.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.createContext(
        "/api/v1/sessions",
        exchange -> {
          var response =
              exchange.getRequestURI().getPath().endsWith("/chosen")
                  ? "{\"sessionId\":\"chosen\",\"projectId\":\"one\"}"
                  : "{\"items\":[{\"sessionId\":\"latest\"}]}";
          var bytes = response.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try (var backend =
            new BackendClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "fixture");
        var client =
            new TerminalClient(
                backend,
                directory,
                true,
                TerminalBuilder.builder()
                    .system(false)
                    .dumb(true)
                    .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                    .build())) {
      client.command("/project select one");
      client.command("/session select chosen");
      client.command("/project cd .");
      assertEquals("chosen", client.sessionId());
    } finally {
      server.stop(0);
    }
  }

  /** 軽量CLIでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void projectAddDoesNotChangeSelectionAndCdUsesSelectedDirectory() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var registrations = new java.util.ArrayList<String>();
    server.createContext(
        "/api/v1/projects",
        exchange -> {
          String response;
          if (exchange.getRequestMethod().equals("POST")) {
            var body = BackendClient.JSON.readTree(exchange.getRequestBody());
            registrations.add(body.path("path").asText());
            response =
                "{\"id\":\"two\",\"name\":\"Two\",\"path\":"
                    + BackendClient.JSON.writeValueAsString(directory.resolve("child").toString())
                    + "}";
          } else
            response =
                "[{\"id\":\"one\",\"name\":\"One\",\"path\":"
                    + BackendClient.JSON.writeValueAsString(directory.toString())
                    + "},{\"id\":\"two\",\"name\":\"Two\",\"path\":"
                    + BackendClient.JSON.writeValueAsString(directory.resolve("child").toString())
                    + "}]";
          var bytes = response.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.createContext(
        "/api/v1/sessions",
        exchange -> {
          var bytes = "{\"items\":[]}".getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try (var backend =
            new BackendClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "fixture");
        var client =
            new TerminalClient(
                backend,
                directory,
                true,
                TerminalBuilder.builder()
                    .system(false)
                    .dumb(true)
                    .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                    .build())) {
      client.command("/project select one");
      client.command("/project add child");
      assertEquals("one", client.projectId());
      client.command("/project cd child");
      assertEquals("two", client.projectId());
      assertEquals(
          java.util.List.of(
              directory.resolve("child").toString(), directory.resolve("child").toString()),
          registrations);
      assertThrows(
          IllegalArgumentException.class, () -> client.command("/project add child extra"));
    } finally {
      server.stop(0);
    }
  }

  /** 軽量CLIでの対話で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void quotedWindowsPathsRetainBackslashes() {
    assertEquals(
        java.util.List.of("/project", "register", "C:\\project with spaces\\rei"),
        TerminalClient.commandWords("/project register \"C:\\project with spaces\\rei\""));
  }

  /** 軽量CLIでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void unknownSlashCommandsNeverSendAChatRequest() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var requests = new java.util.concurrent.atomic.AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    server.start();
    try (var backend =
            new BackendClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "fixture");
        var client =
            new TerminalClient(
                backend,
                directory,
                true,
                TerminalBuilder.builder()
                    .system(false)
                    .dumb(true)
                    .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                    .build())) {
      assertThrows(
          IllegalArgumentException.class, () -> client.command("/unsupported do something"));
      assertEquals(0, requests.get());
    } finally {
      server.stop(0);
    }
  }

  /** 軽量CLIでの対話で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void twoClientsKeepProjectSelectionsSeparate() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/projects",
        exchange -> {
          var bytes =
              "[{\"id\":\"one\",\"name\":\"One\"},{\"id\":\"two\",\"name\":\"Two\"}]".getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.createContext(
        "/api/v1/sessions",
        exchange -> {
          var bytes = "{\"items\":[]}".getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    try (var firstBackend = new BackendClient(endpoint, "fixture");
        var secondBackend = new BackendClient(endpoint, "fixture");
        var first =
            new TerminalClient(
                firstBackend,
                directory,
                true,
                TerminalBuilder.builder()
                    .system(false)
                    .dumb(true)
                    .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                    .build());
        var second =
            new TerminalClient(
                secondBackend,
                directory,
                true,
                TerminalBuilder.builder()
                    .system(false)
                    .dumb(true)
                    .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                    .build())) {
      first.command("/project select one");
      second.command("/project select two");
      assertEquals("one", first.projectId());
      assertEquals("two", second.projectId());
      assertThrows(IllegalArgumentException.class, () -> first.command("/project select missing"));
      assertEquals("one", first.projectId());
    } finally {
      server.stop(0);
    }
  }
}
