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
import org.jline.terminal.impl.DumbTerminal;
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

  @Test
  void keyboardSelectionResumesSecondTaskAndUsesItsSession() throws Exception {
    verifyResume(false, "\033[B\r");
  }

  /** HTTP と SSE を使って再開と後続の送信先を確認する。 */
  private void verifyResume(boolean fail) throws Exception {
    verifyResume(fail, null);
  }

  private void verifyResume(boolean fail, String keys) throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var submittedSession = new AtomicReference<String>();
    var output = new ByteArrayOutputStream();
    var resumedTask = new AtomicReference<String>();
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
            resumedTask.set(e.getRequestURI().getPath().split("/")[6]);
            if (fail) {
              e.sendResponseHeaders(409, -1);
              e.close();
            } else reply(e, "{\"runId\":\"resumed\",\"sessionId\":\"target\"}");
          } else if (e.getRequestURI().getPath().endsWith("/checkpoints")) {
            reply(
                e,
                "[{\"taskId\":\"first\",\"status\":\"INTERRUPTED\",\"request\":\"First\"},"
                    + "{\"taskId\":\"second\",\"status\":\"INTERRUPTED\",\"request\":\"Second\"}]");
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
                keys == null
                    ? new DumbTerminal(new ByteArrayInputStream(new byte[0]), output)
                    : interactiveTerminal(keys, output))) {
      client.command("/project select one");
      client.command("/session switch previous");
      if (fail)
        assertThrows(BackendClient.ApiException.class, () -> client.command("/resume task"));
      else {
        client.command(keys == null ? "/resume task" : "/resume");
        assertEquals(keys == null ? "task" : "second", resumedTask.get());
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

  /** In-memory input with explicit TTY capability, without opening a real console. */
  private static org.jline.terminal.Terminal interactiveTerminal(
      String keys, ByteArrayOutputStream output) throws IOException {
    var terminal =
        new DumbTerminal(new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)), output);
    terminal.setSize(new org.jline.terminal.Size(100, 12));
    var provider =
        java.lang.reflect.Proxy.newProxyInstance(
            ResumeSessionClientTest.class.getClassLoader(),
            new Class<?>[] {org.jline.terminal.spi.TerminalProvider.class},
            (proxy, method, args) -> method.getName().equals("isSystemStream") ? true : null);
    return (org.jline.terminal.Terminal)
        java.lang.reflect.Proxy.newProxyInstance(
            ResumeSessionClientTest.class.getClassLoader(),
            new Class<?>[] {org.jline.terminal.spi.TerminalExt.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "getProvider":
                  return provider;
                case "getSystemStream":
                  return org.jline.terminal.spi.SystemStream.Input;
                case "getType":
                  return "xterm";
                default:
                  try {
                    return method.invoke(terminal, args);
                  } catch (java.lang.reflect.InvocationTargetException error) {
                    throw error.getCause();
                  }
              }
            });
  }

  /** サーバー応答を UTF-8 で返す。 */
  private static void reply(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
