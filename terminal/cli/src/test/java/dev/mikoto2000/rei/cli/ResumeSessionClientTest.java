package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 再開した Run の表示先と追加入力の会話が一致することを検証する。 */
class ResumeSessionClientTest {
  /** 履歴の保存先。 */
  @TempDir Path directory;

  /** 再開の出力を購読し、後続の入力を同じ会話へ送信する。 */
  @Test
  void resumeWatchesOutputAndSelectsSessionForNextInput() throws Exception {
    verifyResume(false);
  }

  /** 再開の受付に失敗した場合は元の会話を維持する。 */
  @Test
  void rejectedResumePreservesSession() throws Exception {
    verifyResume(true);
  }

  /** HTTP と SSE を使って再開と後続の送信先を確認する。 */
  private void verifyResume(boolean fail) throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var submittedSession = new AtomicReference<String>();
    var output = new ByteArrayOutputStream();
    server.createContext("/api/v1/cli/catalog", e -> reply(e, "{\"commands\":[]}"));
    server.createContext("/api/v1/models/current", e -> reply(e, "{}"));
    server.createContext("/api/v1/resume-overview", e -> reply(e, "{\"enabled\":false}"));
    server.createContext(
        "/api/v1/sessions",
        e ->
            reply(
                e,
                e.getRequestURI().getPath().endsWith("/previous")
                    ? "{\"sessionId\":\"previous\",\"projectId\":\"one\"}"
                    : "{\"items\":[]}"));
    server.createContext(
        "/api/v1/projects",
        e -> {
          if (e.getRequestURI().getPath().endsWith("/resume")) {
            if (fail) {
              e.sendResponseHeaders(409, -1);
              e.close();
            } else reply(e, "{\"runId\":\"resumed\",\"sessionId\":\"target\"}");
          } else reply(e, "[{\"id\":\"one\",\"name\":\"One\"}]");
        });
    server.createContext(
        "/api/v1/chat",
        e -> {
          submittedSession.set(
              BackendClient.JSON.readTree(e.getRequestBody()).path("sessionId").asText());
          reply(e, "{\"runId\":\"next\",\"sessionId\":\"" + submittedSession.get() + "\"}");
        });
    server.createContext(
        "/api/v1/runs",
        e -> {
          String id = e.getRequestURI().getPath().split("/")[4];
          e.getResponseHeaders().set("Content-Type", "text/event-stream");
          reply(
              e,
              "id: 1\nevent: message.completed\ndata: {\"runId\":\""
                  + id
                  + "\",\"timestamp\":\"2026-10-22T00:00:00Z\",\"payload\":{\"messageId\":\"m\",\"role\":\"assistant\",\"text\":\"resumed output\"}}\n\n"
                  + "id: 2\nevent: agent.run.completed\ndata: {\"runId\":\""
                  + id
                  + "\",\"timestamp\":\"2026-10-22T00:00:00Z\",\"payload\":{}}\n\n");
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
      client.command("/session switch previous");
      if (fail)
        assertThrows(BackendClient.ApiException.class, () -> client.command("/resume task"));
      else {
        client.command("/resume task");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!output.toString(StandardCharsets.UTF_8).contains("resumed output")
            && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("resumed output"));
      }
      assertEquals(fail ? "previous" : "target", client.sessionId());
      client.command("/chat follow-up");
      assertEquals(fail ? "previous" : "target", submittedSession.get());
    } finally {
      server.stop(0);
    }
  }

  /** サーバー応答を UTF-8 で返す。 */
  private static void reply(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
