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
import java.util.concurrent.atomic.AtomicInteger;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatEndpointClientTest {
  @TempDir Path directory;

  private static final String ENTRY =
      """
      {"profile":{"id":"local","model":"local-model","provider":"openai-compatible",
        "api":"CHAT_COMPLETIONS","authentication":"none","usagePath":"local-llm",
        "capabilities":{"effective":{"STREAMING":"UNKNOWN"}}},
       "active":true,"authenticationState":"not-required","generationSettings":{},
       "unappliedSettings":{},"diagnostics":[],
       "selectionScope":"process-wide; next Run; resets on restart; inference not verified"}
      """;

  @Test
  void directSelectionAndMetadataWorkWithoutAProjectOrSession() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.client.command("/llm chat list");
      assertTrue(fixture.text().contains("[active]"));
      fixture.client.command("/llm chat show");
      assertTrue(fixture.text().contains("capabilities"));
      fixture.client.command("/llm chat use local");
      assertEquals(1, fixture.mutations.get());
      assertTrue(fixture.text().contains("Selected:"));
      assertTrue(fixture.text().contains("next Run"));
      assertTrue(fixture.text().contains("not verified"));
    }
  }

  @Test
  void groupAndHelpExplainActionsWithoutContactingTheConnection() throws Exception {
    try (var fixture = new Fixture()) {
      for (var command : List.of("/llm chat", "/llm chat --help", "/llm chat use --help"))
        fixture.client.command(command);
      assertTrue(fixture.text().contains("list|show|use"));
      assertEquals(0, fixture.requests.get());
    }
  }

  @Test
  void noninteractiveUseExplainsDirectSyntaxAndDoesNotSelectADefault() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.client.command("/llm chat use");
      assertEquals(0, fixture.mutations.get());
      assertTrue(fixture.text().contains("/llm chat use <endpoint-name>"));
    }
  }

  @Test
  void completionUsesRegisteredMetadataAndKeepsNamesInEndpointArgument() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.client.command("/llm chat list");
      assertTrue(fixture.client.completionValues(List.of("/llm", ""), 1, "").contains("chat"));
      assertTrue(
          fixture
              .client
              .completionValues(List.of("/llm", "chat", ""), 2, "")
              .containsAll(List.of("list", "show", "use")));
      assertEquals(
          List.of("local"),
          fixture.client.completionValues(List.of("/llm", "chat", "use", "lo"), 3, "lo"));
      assertTrue(
          fixture
              .client
              .completionValues(List.of("/llm", "capture", "use", "lo"), 3, "lo")
              .isEmpty());
    }
  }

  private final class Fixture implements AutoCloseable {
    final AtomicInteger mutations = new AtomicInteger();
    final AtomicInteger requests = new AtomicInteger();
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    final HttpServer server;
    final BackendClient backend;
    final TerminalClient client;

    Fixture() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/api/v1/llm/chat",
          exchange -> {
            requests.incrementAndGet();
            if (exchange.getRequestMethod().equals("PUT")) {
              assertEquals(
                  "{\"endpoint\":\"local\"}",
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
              mutations.incrementAndGet();
            }
            var bytes =
                (exchange.getRequestURI().getPath().endsWith("/current")
                        ? ENTRY
                        : "[" + ENTRY + "]")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
      backend =
          new BackendClient(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "fixture");
      client =
          new TerminalClient(
              backend,
              directory,
              true,
              new DumbTerminal(new ByteArrayInputStream(new byte[0]), output));
    }

    String text() {
      return output.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws Exception {
      try {
        client.close();
      } finally {
        backend.close();
        server.stop(0);
      }
    }
  }
}
