package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.llm.capture.CaptureInterceptor;
import dev.mikoto2000.rei.llm.capture.CaptureStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

class ScopedCapturingChatModelTest {
  @TempDir Path root;

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void captureUsesTheScopedClientInsteadOfRebuildingFromUnrelatedDefaults(boolean streaming) throws Exception {
    exercise(streaming, false);
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void captureCreationFailureKeepsTheSamePinnedDelegate(boolean streaming) throws Exception {
    exercise(streaming, true);
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void remoteCliWebCaptureUsesTheSamePinnedReceiver(boolean streaming) throws Exception {
    exercise(streaming, false, AgentRunContext.RequestSource.WEB);
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void mcpDoesNotConsumeKeyboardCaptureEvenWithAnOwnedRunEntry(boolean streaming) throws Exception {
    exercise(streaming, false, AgentRunContext.RequestSource.MCP);
  }

  private void exercise(boolean streaming, boolean failCapture) throws Exception {
    exercise(streaming, failCapture, AgentRunContext.RequestSource.SHELL);
  }

  private void exercise(boolean streaming, boolean failCapture, AgentRunContext.RequestSource source) throws Exception {
    try (var actual = new Fixture(streaming); var unrelated = new Fixture(streaming)) {
      var store = new CaptureStore();
      store.reserve("client", "conversation");
      store.accept("client", "conversation", "submission", "run");
      var defaultOptions = OpenAiChatOptions.builder().model("probe").baseUrl(unrelated.url())
          .apiKey("PRIVATE_UNRELATED_DEFAULT").maxRetries(0).build();
      var requestOptions = OpenAiChatOptions.builder().model("probe").maxRetries(0)
          .toolContext(Map.of(AgentRunContext.class.getName(), new AgentRunContext("run", "conversation", root, "project", source)))
          .build();
      var pinned = PinnedOpenAiClients.open(actual.endpoint(), LlmAuthentication.NONE,
          () -> "PRIVATE_UNUSED", actual.transport, List.of());
      try {
        var delegate = OpenAiChatModel.builder().options(defaultOptions)
            .openAiClient(pinned).openAiClientAsync(pinned.async()).build();
        var creations = new AtomicInteger();
        var captured = new CapturingChatModel(delegate, store, capture -> {
          creations.incrementAndGet();
          if (failCapture) throw new IllegalStateException("fixture capture initialization failed");
          return PinnedOpenAiClients.open(actual.endpoint(), LlmAuthentication.NONE,
              () -> "PRIVATE_UNUSED", actual.transport, List.of(capture));
        });
        var prompt = new Prompt("fixture", requestOptions);
        if (streaming) assertThat(captured.stream(prompt).collectList().block(Duration.ofSeconds(5))).isNotEmpty();
        else assertThat(captured.call(prompt).getResult().getOutput().getText()).isEqualTo("ok");
        assertThat(creations).hasValue(source == AgentRunContext.RequestSource.MCP ? 0 : 1);
        assertThat(actual.received).hasSize(1);
        assertThat(actual.auth).containsExactly("<none>");
        assertThat(unrelated.received).isEmpty();
        if (failCapture || source == AgentRunContext.RequestSource.MCP) assertThat(store.attempts("run")).isEmpty();
        else {
          var attempt = store.attempts("run");
          assertThat(attempt).hasSize(1);
          assertThat(store.body(attempt.getFirst().attemptId())).isEqualTo(actual.received.getFirst());
        }
      } finally { pinned.close(); }
    }
  }

  private static final class Fixture implements AutoCloseable {
    final HttpServer server;
    final okhttp3.OkHttpClient transport = new okhttp3.OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    final List<byte[]> received = new CopyOnWriteArrayList<>();
    final List<String> auth = new CopyOnWriteArrayList<>();

    Fixture(boolean streaming) throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/v1/chat/completions", exchange -> {
        received.add(exchange.getRequestBody().readAllBytes());
        auth.add(java.util.Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"), "<none>"));
        var chunk = "{\"id\":\"chat\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"probe\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":null}]}";
        String response = streaming ? "data: " + chunk + "\n\ndata: [DONE]\n\n"
            : "{\"id\":\"chat\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"probe\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
        finally { exchange.close(); }
      });
      server.start();
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }

    ResolvedLlmEndpoint endpoint() {
      return new ResolvedLlmEndpoint("fixture", 1, LlmApiType.CHAT_COMPLETIONS, url(), "fixture", "probe",
          new LlmCapabilities(ReasoningReplayMode.NONE, 36, Set.of()), null, null, Duration.ofSeconds(5));
    }

    public void close() {
      server.stop(0);
      transport.connectionPool().evictAll();
      transport.dispatcher().executorService().shutdownNow();
    }
  }
}
