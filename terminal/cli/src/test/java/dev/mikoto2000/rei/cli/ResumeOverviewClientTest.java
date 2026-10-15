package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ResumeOverviewClientTest {
  @TempDir Path directory;

  @Test
  void manualOverviewUsesReadOnlyRoute() {
    var route = RemoteCommands.route(List.of("/resume", "overview"), "one", null);
    assertEquals("GET", route.method());
    assertEquals("/api/v1/projects/one/resume-overview", route.path());
  }

  @Test
  void selectionHistoryReconnectAndFailureStayClientLocal() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var revision = new java.util.concurrent.atomic.AtomicInteger(1);
    var unavailable = new java.util.concurrent.atomic.AtomicBoolean();
    server.createContext("/api/v1/cli/catalog", e -> reply(e, "{\"commands\":[]}"));
    server.createContext("/api/v1/models/current", e -> reply(e, "{}"));
    server.createContext("/api/v1/sessions", e -> reply(e, "{\"items\":[]}"));
    server.createContext(
        "/api/v1/resume-overview",
        e ->
            reply(
                e,
                "{\"enabled\":true,\"projects\":[{\"projectId\":\"one\",\"projectName\":\"One\",\"count\":2}]}"));
    server.createContext(
        "/api/v1/projects",
        e -> {
          if (e.getRequestURI().getPath().endsWith("resume-overview")) {
            if (unavailable.get()) {
              e.sendResponseHeaders(503, -1);
              e.close();
              return;
            }
            String p = e.getRequestURI().getPath().split("/")[4];
            reply(
                e,
                "{\"projectId\":\""
                    + p
                    + "\",\"projectName\":\""
                    + p
                    + "\",\"count\":2,\"fingerprint\":\""
                    + p
                    + revision.get()
                    + "\",\"latest\":{\"taskId\":\"task-"
                    + p
                    + "\",\"revision\":"
                    + revision.get()
                    + ",\"headRevision\":"
                    + revision.get()
                    + ",\"savedAt\":\"2026-10-13T09:42:00Z\",\"savedStatus\":\"INTERRUPTED\",\"request\":\"request-"
                    + p
                    + "\",\"completed\":\"未記録\",\"remaining\":\"test\",\"notice\":\"要照合\",\"fallback\":false}}");
          } else reply(e, "[{\"id\":\"one\",\"name\":\"One\"},{\"id\":\"two\",\"name\":\"Two\"}]");
        });
    server.start();
    try {
      for (int connection = 0; connection < 2; connection++) {
        var output = new ByteArrayOutputStream();
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
          assertTrue(
              output.toString(java.nio.charset.StandardCharsets.UTF_8).contains("request-one"));
          output.reset();
          client.command("/project select one");
          assertFalse(
              output.toString(java.nio.charset.StandardCharsets.UTF_8).contains("request-one"));
          client.command("/project select two");
          output.reset();
          client.command("/project select one");
          assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8).contains("変更なし"));
          output.reset();
          revision.incrementAndGet();
          client.command("/project select one");
          assertTrue(
              output.toString(java.nio.charset.StandardCharsets.UTF_8).contains("request-one"));
          output.reset();
          client.command("/resume overview");
          assertTrue(
              output.toString(java.nio.charset.StandardCharsets.UTF_8).contains("request-one"));
          unavailable.set(true);
          assertDoesNotThrow(() -> client.command("/project select two"));
          assertEquals("two", client.projectId());
          unavailable.set(false);
        }
      }
    } finally {
      server.stop(0);
    }
  }

  private static void reply(com.sun.net.httpserver.HttpExchange e, String text) throws IOException {
    var bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    e.getResponseHeaders().set("Content-Type", "application/json");
    e.sendResponseHeaders(200, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }
}
