package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.mikoto2000.rei.core.completion.ApprovalActionMenu;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 終端承認状態の結果と状態の契約を検証するテスト。 */
class TerminalApprovalStateTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 軽量CLIでの対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void snapshotsFilterDecisionsExpiryCancellationAndOtherSessionsWithoutMutations()
      throws Exception {
    var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var now = Instant.now();
    var json = BackendClient.JSON;
    var rows = json.createArrayNode();
    add(rows, "pending", "s", "PENDING", "WAITING_APPROVAL", now.plusSeconds(120));
    add(rows, "approved", "s", "APPROVED", "WAITING_APPROVAL", now.plusSeconds(120));
    add(rows, "denied", "s", "DENIED", "WAITING_APPROVAL", now.plusSeconds(120));
    add(rows, "cancelled", "s", "PENDING", "CANCELLED", now.plusSeconds(120));
    add(rows, "expired", "s", "PENDING", "WAITING_APPROVAL", now.minusSeconds(1));
    add(rows, "other", "other", "PENDING", "WAITING_APPROVAL", now.plusSeconds(120));
    add(rows, "legacy-cancelled", "s", "PENDING", "UNAVAILABLE", now.plusSeconds(120));
    add(rows, "legacy-waiting", "s", "PENDING", "UNAVAILABLE", now.plusSeconds(120));
    server.createContext(
        "/",
        exchange -> {
          requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
          String response =
              exchange.getRequestURI().getPath().endsWith("/approvals")
                  ? rows.toString()
                  : exchange.getRequestURI().getPath().endsWith("legacy-cancelled")
                      ? "{\"status\":\"CANCELLED\"}"
                      : "{\"status\":\"WAITING_APPROVAL\"}";
          byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try (var terminal =
            TerminalBuilder.builder()
                .system(false)
                .dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                .build();
        var backend =
            new BackendClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "fixture");
        var client = new TerminalClient(backend, directory, true, terminal)) {
      assertEquals(
          List.of("pending", "legacy-waiting"),
          client.pendingApprovals(new ApprovalActionMenu.Owner("p", "s")));
      assertEquals(
          List.of(
              "GET /api/v1/projects/p/approvals",
              "GET /api/v1/runs/legacy-cancelled",
              "GET /api/v1/runs/legacy-waiting"),
          requests);
    } finally {
      server.stop(0);
    }
  }

  /** 検証用に、新たな対象を登録し、後から利用できるようにする。 */
  private static void add(
      com.fasterxml.jackson.databind.node.ArrayNode rows,
      String id,
      String session,
      String status,
      String continuation,
      Instant expires) {
    rows.addObject()
        .put("id", id)
        .put("projectId", "p")
        .put("sessionId", session)
        .put("runId", id)
        .put("status", status)
        .put("continuationState", continuation)
        .put("expiresAt", expires.toString());
  }
}
