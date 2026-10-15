package dev.mikoto2000.rei.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.conversation.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.web.*;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.*;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.*;

@Tag("integration")
class McpHttpIntegrationTest {
  @TempDir Path root;

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
    @Bean
    static org.springframework.context.support.PropertySourcesPlaceholderConfigurer placeholders() {
      return new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();
    }

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }

    @Bean
    dev.mikoto2000.rei.dot.DotRequestRepository dotRequests(
        @Value("${rei.data-dir}") String root, Clock clock) {
      return new dev.mikoto2000.rei.dot.DotRequestRepository(Path.of(root), clock);
    }

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

    @Bean
    ProjectRegistry projects(@Value("${rei.data-dir}") String root) {
      return new ProjectRegistry(Path.of(root, "projects.json"));
    }

    @Bean
    AgentEventBus bus() {
      return new InMemoryAgentEventBus();
    }

    @Bean
    AgentEventFactory events(Clock clock) {
      return new AgentEventFactory(clock);
    }

    @Bean
    Queue<Runnable> pending() {
      return new java.util.concurrent.ConcurrentLinkedQueue<>();
    }

    @Bean
    ConversationInputRouter router(
        Queue<Runnable> pending, AgentEventBus bus, AgentEventFactory events) {
      return new ConversationInputRouter(
          pending::add,
          (c, p, q) -> bus.publish(events.runCompleted(c.runId(), 0).withOwnership(c)));
    }

    @Bean
    RunRegistry registry(Clock clock) {
      return new RunRegistry(clock);
    }

    @Bean
    SessionRegistry sessions(Clock clock) {
      return new SessionRegistry(clock);
    }

    @Bean
    CommandCancellationService cancellation() {
      return new CommandCancellationService();
    }

    @Bean
    RunService runs(
        RunRegistry registry,
        AgentEventBus bus,
        AgentEventFactory events,
        CommandCancellationService cancellation,
        ConversationInputRouter router) {
      return new RunService(registry, bus, events, cancellation, router::cancelQueued);
    }

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

    @Bean
    dev.mikoto2000.rei.core.policy.ToolApprovalRepository approvals() {
      var result = mock(dev.mikoto2000.rei.core.policy.ToolApprovalRepository.class);
      when(result.list(anyString())).thenReturn(List.of());
      return result;
    }
  }

  @org.springframework.web.bind.annotation.RestController
  static class Probe {
    @org.springframework.web.bind.annotation.GetMapping("/api/probe")
    String probe() {
      return "ok";
    }
  }

  int availablePort() throws Exception {
    try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      return socket.getLocalPort();
    }
  }

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
