package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

/**
 * Real owned HTTP fixtures; byte limits apply to serialized requests and consumed response bytes.
 */
class PinnedWireLimitsTest {
  @Test
  void anOversizedSerializedRequestNeverReachesHttp() throws Exception {
    try (var fixture = new Fixture(false, false)) {
      var model = fixture.model(128, 8192);
      assertThatThrownBy(() ->
              model.call(new Prompt("PRIVATE_OVERSIZED_INPUT".repeat(128), model.getOptions())))
          .isInstanceOfSatisfying(
              ResponsesTransportException.class,
              failure -> assertThat(failure.diagnosticCode()).isEqualTo("request_size_limit"))
          .hasMessageNotContaining("PRIVATE_OVERSIZED_INPUT");
      assertThat(fixture.requests.get()).isZero();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void anOversizedChunkedResponseStopsWithoutRetry(boolean streaming) throws Exception {
    try (var fixture = new Fixture(streaming, false)) {
      fixture.answer = "PRIVATE_OVERSIZED_OUTPUT".repeat(128);
      var model = fixture.model(8192, 128);
      var prompt = new Prompt("fixture", model.getOptions());
      assertThatThrownBy(
              () -> {
                if (streaming) model.stream(prompt).collectList().block(Duration.ofSeconds(5));
                else model.call(prompt);
              })
          .isInstanceOfSatisfying(
              ResponsesTransportException.class,
              failure -> assertThat(failure.diagnosticCode()).isEqualTo("response_size_limit"))
          .hasMessageNotContaining("PRIVATE_OVERSIZED_OUTPUT");
      assertThat(fixture.requests.get()).isEqualTo(1);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void boundedRequestsAndResponsesStillUseTheSameSdk(boolean streaming) throws Exception {
    try (var fixture = new Fixture(streaming, false)) {
      var model = fixture.model(8192, 8192);
      var prompt = new Prompt("fixture", model.getOptions());
      if (streaming)
        assertThat(model.stream(prompt).collectList().block(Duration.ofSeconds(5))).isNotEmpty();
      else assertThat(model.call(prompt).getResult().getOutput().getText()).isEqualTo("done");
      assertThat(fixture.requests.get()).isEqualTo(1);
    }
  }

  @Test
  void receivingADeltaDoesNotTurnAResponseSizeFailureIntoSuccess() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      var model = fixture.model(8192, 1024);
      var observed = new AtomicInteger();
      assertThatThrownBy(() ->
              model.stream(new Prompt("fixture", model.getOptions()))
                  .doOnNext(chunk -> {
                    if (chunk.getResults().stream()
                        .anyMatch(result ->
                            java.util.Objects.toString(result.getOutput().getText(), "")
                                .contains("partial"))) {
                      observed.incrementAndGet();
                      fixture.deltaSeen.countDown();
                    }
                  })
                  .collectList()
                  .block(Duration.ofSeconds(8)))
          .isInstanceOfSatisfying(
              ResponsesTransportException.class,
              failure -> assertThat(failure.diagnosticCode()).isEqualTo("response_size_limit"));
      assertThat(observed.get()).isGreaterThan(0);
      assertThat(fixture.requests.get()).isEqualTo(1);
    }
  }

  private static final class Fixture implements AutoCloseable {
    final com.sun.net.httpserver.HttpServer server;
    final AtomicInteger requests = new AtomicInteger();
    final CountDownLatch deltaSeen = new CountDownLatch(1);
    final okhttp3.OkHttpClient transport =
        new okhttp3.OkHttpClient.Builder()
            .proxy(java.net.Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build();
    final List<com.openai.client.OpenAIClient> clients = new java.util.ArrayList<>();
    String answer = "done";

    Fixture(boolean streaming, boolean waitForDelta) throws Exception {
      server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/v1/responses",
          exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange
                .getResponseHeaders()
                .set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
              if (waitForDelta) {
                body.write(
                    event(
                            "response.output_text.delta",
                            "\"item_id\":\"msg_fixture\",\"output_index\":0,\"content_index\":0,\"delta\":\"partial\"")
                        .getBytes(StandardCharsets.UTF_8));
                body.flush();
                try {
                  if (!deltaSeen.await(5, TimeUnit.SECONDS))
                    throw new java.io.IOException("Fixture delta was not observed");
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new java.io.IOException("Fixture interrupted");
                }
                body.write(
                    (":" + "padding".repeat(1024) + "\n\n").getBytes(StandardCharsets.UTF_8));
              }
              var completed =
                  "{\"id\":\"resp_fixture\",\"object\":\"response\",\"created_at\":0,\"status\":\"completed\","
                      + "\"model\":\"fixture-model\",\"output\":[{\"type\":\"message\",\"id\":\"msg_fixture\",\"role\":\"assistant\","
                      + "\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\""
                      + answer
                      + "\",\"annotations\":[]}]}],\"usage\":{\"input_tokens\":3,\"output_tokens\":2,\"total_tokens\":5}}";
              body.write(
                  (streaming ? event("response.completed", "\"response\":" + completed) : completed)
                      .getBytes(StandardCharsets.UTF_8));
            } finally {
              exchange.close();
            }
          });
      server.start();
    }

    ResponsesAdapter model(int requestLimit, int responseLimit) {
      var pin =
          new RunLlmConnection(
              "wire-limit",
              true,
              LlmProvider.OPENAI_COMPATIBLE,
              LlmAuthentication.NONE,
              null,
              null,
              new ResolvedLlmEndpoint(
                  "wire-limit",
                  1,
                  LlmApiType.RESPONSES,
                  "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                  "chat-endpoint:wire-limit",
                  "fixture-model",
                  new LlmCapabilities(ReasoningReplayMode.NONE, 36, Set.of()),
                  64,
                  null,
                  Duration.ofSeconds(5)),
              Map.of(
                  LlmEndpointCapabilities.Capability.RESPONSES,
                  LlmEndpointCapabilities.Support.SUPPORTED,
                  LlmEndpointCapabilities.Capability.STREAMING,
                  LlmEndpointCapabilities.Support.SUPPORTED,
                  LlmEndpointCapabilities.Capability.MAX_OUTPUT_TOKENS,
                  LlmEndpointCapabilities.Support.SUPPORTED),
              Map.of("max_output_tokens", 64),
              Map.of(
                  "request_limit_bytes",
                  Integer.toString(requestLimit),
                  "response_limit_bytes",
                  Integer.toString(responseLimit)));
      var client =
          PinnedOpenAiClients.open(
              pin,
              () -> {
                throw new AssertionError("No credentials");
              },
              transport,
              List.of(),
              () -> {});
      clients.add(client);
      return ResponsesAdapter.createForRun(
          pin, (OpenAiResponsesChatOptions) RunNativeOptions.options(pin), client);
    }

    static String event(String type, String fields) {
      return "event: "
          + type
          + "\ndata: {\"type\":\""
          + type
          + "\",\"sequence_number\":1,"
          + fields
          + "}\n\n";
    }

    public void close() {
      clients.forEach(com.openai.client.OpenAIClient::close);
      server.stop(0);
      transport.connectionPool().evictAll();
      transport.dispatcher().executorService().shutdown();
    }
  }
}
