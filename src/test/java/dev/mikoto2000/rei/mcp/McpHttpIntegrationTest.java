package dev.mikoto2000.rei.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.run.ChatSubmitService;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.SessionRegistry;
import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.conversation.SessionHistoryConfiguration;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.web.SecurityConfig;
import dev.mikoto2000.rei.web.WebApplication;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** MCPHTTP通信外部連携の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class McpHttpIntegrationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証対象に必要な依存先と設定を組み立てるテスト用構成。 */
  @Configuration(proxyBeanMethods = false)
  @Import({
    McpConfiguration.class,
    SecurityConfig.class,
    SessionHistoryConfiguration.class,
    Probe.class
  })
  @ImportAutoConfiguration({
    org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
        .class,
    org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.class,
    org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration.class,
    org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class,
    org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration
        .class,
    org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration.class,
    org.springframework.ai.mcp.server.webmvc.autoconfigure
        .McpServerStreamableHttpWebMvcAutoConfiguration.class
  })
  static class Config {
    /** {@code placeholders} の条件を再現するための補助情報を返す。 */
    @Bean
    static org.springframework.context.support.PropertySourcesPlaceholderConfigurer placeholders() {
      return new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();
    }

    /** {@code clock} の条件を再現するための補助情報を返す。 */
    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }

    /** {@code dotRequests} の条件を再現するための補助情報を返す。 */
    @Bean
    dev.mikoto2000.rei.dot.DotRequestRepository dotRequests(
        @Value("${rei.data-dir}") String root, Clock clock) {
      return new dev.mikoto2000.rei.dot.DotRequestRepository(Path.of(root), clock);
    }

    /** {@code dotTools} の条件を再現するための補助情報を返す。 */
    @Bean
    dev.mikoto2000.rei.dot.DotMcpTools dotTools(
        dev.mikoto2000.rei.dot.DotRequestRepository repo, ProjectRegistry projects) {
      return new dev.mikoto2000.rei.dot.DotMcpTools(
          repo,
          projects.list().stream()
              .map(ProjectContext::id)
              .collect(java.util.stream.Collectors.toSet()),
          true);
    }

    /** {@code projects} の条件を再現するための補助情報を返す。 */
    @Bean
    ProjectRegistry projects(@Value("${rei.data-dir}") String root) {
      return new ProjectRegistry(Path.of(root, "projects.json"));
    }

    /** {@code bus} の条件を再現するための補助情報を返す。 */
    @Bean
    AgentEventBus bus() {
      return new InMemoryAgentEventBus();
    }

    /** {@code events} の条件を再現するための補助情報を返す。 */
    @Bean
    AgentEventFactory events(Clock clock) {
      return new AgentEventFactory(clock);
    }

    /** {@code pending} の条件を再現するための補助情報を返す。 */
    @Bean
    Queue<Runnable> pending() {
      return new java.util.concurrent.ConcurrentLinkedQueue<>();
    }

    /** {@code router} の条件を再現するための補助情報を返す。 */
    @Bean
    ConversationInputRouter router(
        Queue<Runnable> pending, AgentEventBus bus, AgentEventFactory events) {
      return new ConversationInputRouter(
          pending::add,
          (c, p, q) -> bus.publish(events.runCompleted(c.runId(), 0).withOwnership(c)));
    }

    /** {@code registry} の条件を再現するための補助情報を返す。 */
    @Bean
    RunRegistry registry(Clock clock) {
      return new RunRegistry(clock);
    }

    /** {@code sessions} の条件を再現するための補助情報を返す。 */
    @Bean
    SessionRegistry sessions(Clock clock) {
      return new SessionRegistry(clock);
    }

    /** {@code cancellation} の条件を再現するための補助情報を返す。 */
    @Bean
    CommandCancellationService cancellation() {
      return new CommandCancellationService();
    }

    /** {@code runs} の条件を再現するための補助情報を返す。 */
    @Bean
    RunService runs(
        RunRegistry registry,
        AgentEventBus bus,
        AgentEventFactory events,
        CommandCancellationService cancellation,
        ConversationInputRouter router) {
      return new RunService(registry, bus, events, cancellation, router::cancelQueued);
    }

    /** {@code chats} の条件を再現するための補助情報を返す。 */
    @Bean
    ChatSubmitService chats(
        ProjectRegistry projects,
        SessionRegistry sessions,
        RunRegistry registry,
        SessionLifecycle lifecycle,
        ConversationInputRouter router,
        RunService runs) {
      return new ChatSubmitService(
          projects,
          sessions,
          registry,
          lifecycle,
          (c, p) -> router.submit(c, p, w -> runs.execute(c, w)),
          true);
    }

    /** {@code contexts} の条件を再現するための補助情報を返す。 */
    @Bean
    dev.mikoto2000.rei.workcontext.WorkContextService contexts() {
      var result = mock(dev.mikoto2000.rei.workcontext.WorkContextService.class);
      when(result.current(anyString()))
          .thenAnswer(
              i ->
                  Optional.of(
                      new dev.mikoto2000.rei.workcontext.WorkContext(
                          i.getArgument(0),
                          1,
                          Instant.EPOCH,
                          Instant.EPOCH,
                          null,
                          List.of(),
                          Set.of())));
      return result;
    }

    /** {@code approvals} の条件を再現するための補助情報を返す。 */
    @Bean
    dev.mikoto2000.rei.core.policy.ToolApprovalRepository approvals() {
      var result = mock(dev.mikoto2000.rei.core.policy.ToolApprovalRepository.class);
      when(result.list(anyString())).thenReturn(List.of());
      return result;
    }
  }

  /** 検証条件を再現するための利用可否の確認。 */
  @org.springframework.web.bind.annotation.RestController
  static class Probe {
    /** {@code probe} の条件を再現するための補助情報を返す。 */
    @org.springframework.web.bind.annotation.GetMapping("/api/probe")
    String probe() {
      return "ok";
    }
  }

  /** {@code availablePort} の条件を再現するための補助情報を返す。 */
  int availablePort() throws Exception {
    try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      return socket.getLocalPort();
    }
  }

  /** 検証用に、要求された処理を開始する。 */
  org.springframework.context.ConfigurableApplicationContext start(
      boolean enabled, int port, String project) {
    var app = new SpringApplication(Config.class);
    WebApplication.configure(app, "fixture-secret");
    return app.run(
        "--rei.data-dir=" + root,
        "--rei.web.enabled=true",
        "--server.port=0",
        "--server.address=0.0.0.0",
        "--rei.mcp-server.enabled=" + enabled,
        "--rei.mcp-server.port=" + port,
        "--rei.mcp-server.allowed-projects=" + project,
        "--rei.conversation.concurrent-enabled=true",
        "--spring.ai.mcp.server.enabled=" + enabled,
        "--spring.ai.mcp.server.protocol=STREAMABLE",
        "--spring.ai.mcp.server.type=SYNC",
        "--spring.ai.mcp.server.tool-callback-converter=false",
        "--spring.ai.mcp.server.annotation-scanner.enabled=false",
        "--logging.config=classpath:web-test-logback.xml");
  }

  /** {@code status} の条件を再現するための補助情報を返す。 */
  int status(String url, String body, String origin, String auth) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5));
    if (origin != null) builder.header("Origin", origin);
    if (auth != null) builder.header("Authorization", auth);
    if (body != null)
      builder
          .header("Content-Type", "application/json")
          .header("Accept", "application/json, text/event-stream")
          .POST(HttpRequest.BodyPublishers.ofString(body));
    return HttpClient.newHttpClient()
        .send(builder.build(), HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  /** MCPによる機能の提供で、指定した条件に対する結果と状態の変化を確認する。 */
  @SuppressWarnings("unchecked")
  @Test
  void sdkInitializesAndCallsExactlySixToolsWhileWebBearerRemainsRequired() throws Exception {
    var p = new ProjectRegistry(root.resolve("projects.json")).resolve(root);
    int port = availablePort();
    try (var context = start(true, port, p.id())) {
      int web =
          ((org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext)
                  context)
              .getWebServer()
              .getPort();
      assertEquals(403, status("http://127.0.0.1:" + web + "/mcp", "{}", null, null));
      assertEquals(404, status("http://127.0.0.1:" + port + "/api/probe", null, null, null));
      assertEquals(401, status("http://127.0.0.1:" + web + "/api/probe", null, null, null));
      assertEquals(
          200,
          status("http://127.0.0.1:" + web + "/api/probe", null, null, "Bearer fixture-secret"));
      assertEquals(
          403, status("http://127.0.0.1:" + port + "/mcp", "{}", "https://evil.test", null));
      assertEquals(403, status("http://localhost:" + port + "/mcp", "{}", null, null));
      assertEquals(400, status("http://127.0.0.1:" + port + "/mcp", "{", null, null));
      var tunnelInitialize =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
              .timeout(Duration.ofSeconds(5))
              .header("Content-Type", "application/json")
              .header("Accept", "application/json, text/event-stream")
              .header("X-Forwarded-Client-Cert", "untrusted tunnel metadata")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"tunnel-test\",\"version\":\"1\"}}}"))
              .build();
      assertEquals(
          200,
          HttpClient.newHttpClient()
              .send(tunnelInitialize, HttpResponse.BodyHandlers.discarding())
              .statusCode());
      var addresses =
          Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
              .filter(
                  n -> {
                    try {
                      return n.isUp();
                    } catch (SocketException ignored) {
                      return false;
                    }
                  })
              .flatMap(n -> Collections.list(n.getInetAddresses()).stream())
              .filter(a -> a instanceof Inet4Address && a.isSiteLocalAddress())
              .toList();
      for (var address : addresses) {
        assertEquals(
            403,
            status("http://" + address.getHostAddress() + ":" + web + "/mcp", "{}", null, null));
        assertThrows(
            java.io.IOException.class,
            () -> {
              try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, port), 500);
              }
            });
      }
      var transport =
          HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
              .endpoint("/mcp")
              .build();
      try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()) {
        assertNotNull(client.initialize());
        var expected = new HashSet<>(McpTools.NAMES);
        expected.addAll(dev.mikoto2000.rei.dot.DotMcpTools.NAMES);
        assertEquals(
            expected,
            new HashSet<>(client.listTools().tools().stream().map(McpSchema.Tool::name).toList()));
        assertFalse(
            client
                .callTool(new McpSchema.CallToolRequest("rei_projects_list", Map.of()))
                .isError());
        String dot =
            (String)
                context
                    .getBean(dev.mikoto2000.rei.dot.DotRequestRepository.class)
                    .create(
                        p.id(),
                        "session",
                        null,
                        root.toString(),
                        Map.of(
                            "category",
                            "TECH_RESEARCH",
                            "instruction",
                            "Dummy",
                            "acceptance_criteria",
                            List.of()),
                        Duration.ofHours(1))
                    .get("request_id");
        assertFalse(
            client
                .callTool(new McpSchema.CallToolRequest("rei_requests_list", Map.of()))
                .isError());
        assertFalse(
            client
                .callTool(
                    new McpSchema.CallToolRequest("rei_request_get", Map.of("request_id", dot)))
                .isError());
        String claim = UUID.randomUUID().toString();
        assertFalse(
            client
                .callTool(
                    new McpSchema.CallToolRequest(
                        "rei_request_claim", Map.of("request_id", dot, "claim_id", claim)))
                .isError());
        var receipt =
            client.callTool(
                new McpSchema.CallToolRequest(
                    "rei_request_result_submit",
                    Map.of(
                        "request_id",
                        dot,
                        "claim_id",
                        claim,
                        "submission_id",
                        UUID.randomUUID().toString(),
                        "outcome",
                        "COMPLETED",
                        "summary",
                        "Fixed short report")));
        assertFalse(receipt.isError());
        assertEquals(true, ((Map<?, ?>) receipt.structuredContent()).get("persisted"));
        assertFalse(
            client
                .callTool(
                    new McpSchema.CallToolRequest("rei_context_get", Map.of("project_id", p.id())))
                .isError());
        var submitted =
            client.callTool(
                new McpSchema.CallToolRequest(
                    "rei_task_submit",
                    Map.of(
                        "project_id", p.id(), "instruction", "review", "request_id", "sdk-key")));
        assertFalse(submitted.isError(), submitted.toString());
        var data = (Map<String, Object>) submitted.structuredContent();
        String run = (String) data.get("run_id");
        assertFalse(
            client
                .callTool(
                    new McpSchema.CallToolRequest(
                        "rei_task_status", Map.of("project_id", p.id(), "run_id", run)))
                .isError());
        assertTrue(
            client
                .callTool(
                    new McpSchema.CallToolRequest(
                        "rei_task_result", Map.of("project_id", p.id(), "run_id", run)))
                .isError());
        assertFalse(
            client
                .callTool(
                    new McpSchema.CallToolRequest(
                        "rei_task_cancel", Map.of("project_id", p.id(), "run_id", run)))
                .isError());
      }
    }
  }

  /** MCPによる機能の提供で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void disabledServerHasNoDedicatedListener() throws Exception {
    int port = availablePort();
    try (var context = start(false, port, "")) {
      assertThrows(
          java.io.IOException.class,
          () -> {
            try (var socket = new Socket()) {
              socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            }
          });
    }
  }
}
