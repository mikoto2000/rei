package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

class PinnedResponsesTransportTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void existingResponsesBoundaryUsesTheExplicitSdkForBothCallTypes(boolean streaming)
      throws Exception {
    try (var fixture = new Fixture(streaming)) {
      var pin = fixture.pin();
      var options = (OpenAiResponsesChatOptions) RunNativeOptions.options(pin);
      var adapter =
          ResponsesAdapter.createWithClient(pin.endpoint(), options, fixture.client(pin), false);
      var prompt = new Prompt("fixture", options);
      if (streaming) {
        var chunks = adapter.stream(prompt).collectList().block(Duration.ofSeconds(5));
        assertThat(chunks).isNotEmpty();
        assertThat(
                chunks.stream()
                    .flatMap(chunk -> chunk.getResults().stream())
                    .map(result -> result.getOutput().getText())
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.joining()))
            .contains("done");
      } else assertThat(adapter.call(prompt).getResult().getOutput().getText()).isEqualTo("done");
      assertThat(fixture.requests).hasSize(1);
      assertThat(fixture.authorizations).containsExactly("<none>");
      var sent =
          new com.fasterxml.jackson.databind.ObjectMapper().readTree(fixture.requests.getFirst());
      assertThat(sent.path("model").asText()).isEqualTo("fixture-model");
      assertThat(sent.path("max_output_tokens").asInt()).isEqualTo(64);
      assertThat(sent.path("store").asBoolean(true)).isFalse();
      for (var field : List.of("max_tokens", "previous_response_id", "reasoning", "service_tier"))
        assertThat(sent.has(field)).as(field).isFalse();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"credential", "receiver"})
  void suppliedNativeOptionsCannotReplaceThePinnedSdkConnection(String change) throws Exception {
    try (var fixture = new Fixture(false)) {
      var pin = fixture.pin();
      var builder = ((OpenAiResponsesChatOptions) RunNativeOptions.options(pin)).mutate();
      if (change.equals("credential")) builder.apiKey("PRIVATE_FOREIGN");
      else builder.baseUrl("http://foreign.invalid/v1");
      assertThatThrownBy(() ->
              ResponsesAdapter.createWithClient(
                  pin.endpoint(), builder.build(), fixture.client(pin), false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining("PRIVATE_FOREIGN");
      assertThat(fixture.requests).isEmpty();
    }
  }

  @Test
  void anInjectedClientDoesNotUnlockTheClosedProductionProfile() throws Exception {
    try (var fixture = new Fixture(false)) {
      var pin = fixture.pin();
      assertThatThrownBy(() ->
              ResponsesAdapter.createWithClient(
                  pin.endpoint(),
                  (OpenAiResponsesChatOptions) RunNativeOptions.options(pin),
                  fixture.client(pin),
                  true))
          .isInstanceOf(IllegalStateException.class);
      assertThat(fixture.requests).isEmpty();
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "false,false",
    "false,true",
    "true,false",
    "true,true"
  })
  void declaredNamedResponsesUsesTheCommonModelCache(boolean streaming, boolean omitOutputLimit)
      throws Exception {
    try (var fixture = new Fixture(streaming);
        var models =
            new PinnedChatModels(
                ignored -> {
                  throw new AssertionError("No-auth read credentials");
                },
                ignored -> {},
                null,
                4)) {
      var pin = namedPin(fixture, omitOutputLimit, LlmEndpointCapabilities.Support.SUPPORTED);
      var options = RunNativeOptions.options(pin);
      var model = models.model(pin);
      if (streaming)
        model.stream(new Prompt("fixture", options)).collectList().block(Duration.ofSeconds(5));
      else
        assertThat(model.call(new Prompt("fixture", options)).getResult().getOutput().getText())
            .isEqualTo("done");
      assertThat(fixture.requests).hasSize(1);
      assertThat(fixture.authorizations).containsExactly("<none>");
      var wire =
          new com.fasterxml.jackson.databind.ObjectMapper().readTree(fixture.requests.getFirst());
      assertThat(wire.has("max_output_tokens")).isEqualTo(!omitOutputLimit);
      if (!omitOutputLimit) assertThat(wire.path("max_output_tokens").asInt()).isEqualTo(64);
      for (var field :
          List.of("reasoning", "service_tier", "include", "previous_response_id", "max_tokens"))
        assertThat(wire.has(field)).as(field).isFalse();
      assertThat(wire.path("store").asBoolean(true)).isFalse();
      assertThat(wire.path("stream").asBoolean()).isEqualTo(streaming);
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = LlmEndpointCapabilities.Support.class,
      names = {"UNKNOWN", "UNSUPPORTED"})
  void undeclaredResponsesNeverReachesHttp(LlmEndpointCapabilities.Support support)
      throws Exception {
    try (var fixture = new Fixture(false);
        var models =
            new PinnedChatModels(
                ignored -> {
                  throw new AssertionError("No-auth read credentials");
                },
                ignored -> {},
                null,
                4)) {
      var pin = namedPin(fixture, false, support);
      assertThatThrownBy(() -> models.model(pin))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("RESPONSES");
      assertThat(fixture.requests).isEmpty();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void namedResponsesApiKeysReachOnlyTheSelectedOwnedServer(boolean streaming) throws Exception {
    try (var fixture = new Fixture(streaming);
        var models =
            new PinnedChatModels(ignored -> "owned-fixture-api-key", ignored -> {}, null, 4)) {
      var source = namedPin(fixture, false, LlmEndpointCapabilities.Support.SUPPORTED);
      var pin =
          new RunLlmConnection(
              source.profileId(),
              true,
              source.provider(),
              LlmAuthentication.API_KEY,
              null,
              null,
              source.endpoint(),
              source.capabilities(),
              source.generation());
      var options = RunNativeOptions.options(pin);
      var model = models.model(pin);
      if (streaming)
        model.stream(new Prompt("fixture", options)).collectList().block(Duration.ofSeconds(5));
      else model.call(new Prompt("fixture", options));
      assertThat(fixture.requests).hasSize(1);
      assertThat(fixture.authorizations).containsExactly("Bearer owned-fixture-api-key");
      assertThat(fixture.requests.getFirst()).doesNotContain("owned-fixture-api-key");
    }
  }

  @Test
  void unconfirmedStreamingDoesNotBlockExplicitlySupportedNonStreamingResponses() throws Exception {
    try (var fixture = new Fixture(false);
        var models =
            new PinnedChatModels(
                ignored -> {
                  throw new AssertionError("No credentials");
                },
                ignored -> {},
                null,
                4)) {
      var source = namedPin(fixture, false, LlmEndpointCapabilities.Support.SUPPORTED);
      var capabilities = new java.util.HashMap<>(source.capabilities());
      capabilities.put(
          LlmEndpointCapabilities.Capability.STREAMING, LlmEndpointCapabilities.Support.UNKNOWN);
      var pin =
          new RunLlmConnection(
              source.profileId(),
              true,
              source.provider(),
              source.authentication(),
              null,
              null,
              source.endpoint(),
              capabilities,
              source.generation());
      var options = RunNativeOptions.options(pin);
      var model = models.model(pin);
      assertThat(model.call(new Prompt("fixture", options)).getResult().getOutput().getText())
          .isEqualTo("done");
      assertThatThrownBy(() -> model.stream(new Prompt("fixture", options)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("STREAMING");
      assertThat(fixture.requests).hasSize(1);
    }
  }

  @Test
  void acceptedNamedDeadlineIsIndependentFromReadInactivity() throws Exception {
    try (var fixture = new Fixture(false)) {
      var source = namedPin(fixture, false, LlmEndpointCapabilities.Support.SUPPORTED);
      var original = source.endpoint();
      var pin =
          new RunLlmConnection(
              source.profileId(),
              true,
              source.provider(),
              source.authentication(),
              null,
              null,
              new ResolvedLlmEndpoint(
                  original.endpointId(),
                  original.generation(),
                  original.apiType(),
                  original.baseUrl(),
                  original.credentialRef(),
                  original.model(),
                  original.capabilities(),
                  64,
                  null,
                  Duration.ofSeconds(3)),
              source.capabilities(),
              source.generation(),
              Map.of("connect_timeout", "PT2S", "request_timeout", "PT4S"));
      var observed = new java.util.concurrent.ConcurrentHashMap<String, Long>();
      var client =
          fixture.client(
              pin,
              List.of(
                  new ChatStreamTimeoutInterceptor(Duration.ofSeconds(3), true),
                  chain -> {
                    observed.put("connect", (long) chain.connectTimeoutMillis());
                    observed.put("read", (long) chain.readTimeoutMillis());
                    observed.put(
                        "request",
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                            chain.call().timeout().timeoutNanos()));
                    return chain.proceed(chain.request());
                  }));
      var options = (OpenAiResponsesChatOptions) RunNativeOptions.options(pin);
      var model = ResponsesAdapter.createForRun(pin, options, client);
      assertThat(model.call(new Prompt("fixture", options)).getResult().getOutput().getText())
          .isEqualTo("done");
      assertThat(observed)
          .containsEntry("connect", 2000L)
          .containsEntry("read", 3000L)
          .containsEntry("request", 4000L);
      assertThat(fixture.requests).hasSize(1);
    }
  }

  @Test
  void commonCacheHonorsTheWholeRequestDeadlineEvenWhileDataKeepsArriving() throws Exception {
    try (var fixture = new Fixture(true);
        var models =
            new PinnedChatModels(
                ignored -> {
                  throw new AssertionError("No credentials");
                },
                ignored -> {},
                null,
                4)) {
      fixture.dripMillis = 2000;
      var source = namedPin(fixture, false, LlmEndpointCapabilities.Support.SUPPORTED);
      var endpoint = source.endpoint();
      var pin =
          new RunLlmConnection(
              source.profileId(),
              true,
              source.provider(),
              source.authentication(),
              null,
              null,
              new ResolvedLlmEndpoint(
                  endpoint.endpointId(),
                  endpoint.generation(),
                  endpoint.apiType(),
                  endpoint.baseUrl(),
                  endpoint.credentialRef(),
                  endpoint.model(),
                  endpoint.capabilities(),
                  64,
                  null,
                  Duration.ofSeconds(1)),
              source.capabilities(),
              source.generation(),
              Map.of("request_timeout", "PT0.5S"));
      var model = models.model(pin);
      assertThatThrownBy(() ->
              model.stream(new Prompt("fixture", RunNativeOptions.options(pin)))
                  .collectList()
                  .block(Duration.ofSeconds(5)))
          .isInstanceOfSatisfying(
              ResponsesTransportException.class,
              failure -> assertThat(failure.diagnosticCode()).isEqualTo("timeout"));
      assertThat(fixture.requests).hasSize(1);
    }
  }

  private static RunLlmConnection namedPin(
      Fixture fixture, boolean omitOutputLimit, LlmEndpointCapabilities.Support responses) {
    var source = fixture.pin();
    var endpoint = source.endpoint();
    return new RunLlmConnection(
        source.profileId(),
        true,
        source.provider(),
        source.authentication(),
        null,
        null,
        new ResolvedLlmEndpoint(
            endpoint.endpointId(),
            endpoint.generation(),
            endpoint.apiType(),
            endpoint.baseUrl(),
            "chat-endpoint:fixture",
            endpoint.model(),
            endpoint.capabilities(),
            omitOutputLimit ? null : 64,
            null,
            endpoint.readTimeout()),
        Map.of(
            LlmEndpointCapabilities.Capability.RESPONSES,
            responses,
            LlmEndpointCapabilities.Capability.STREAMING,
            LlmEndpointCapabilities.Support.SUPPORTED,
            LlmEndpointCapabilities.Capability.MAX_OUTPUT_TOKENS,
            omitOutputLimit
                ? LlmEndpointCapabilities.Support.UNSUPPORTED
                : LlmEndpointCapabilities.Support.SUPPORTED),
        omitOutputLimit ? Map.of() : Map.of("max_output_tokens", 64));
  }

  private static final class Fixture implements AutoCloseable {
    final com.sun.net.httpserver.HttpServer server;
    final okhttp3.OkHttpClient transport =
        new okhttp3.OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build();
    final List<com.openai.client.OpenAIClient> clients = new java.util.ArrayList<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> authorizations = new CopyOnWriteArrayList<>();

    volatile int dripMillis;

    Fixture(boolean streaming) throws Exception {
      server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/v1/responses",
          exchange -> {
            requests.add(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorizations.add(
                java.util.Objects.toString(
                    exchange.getRequestHeaders().getFirst("Authorization"), "<none>"));
            var completed =
                """
                {"id":"resp_fixture","object":"response","created_at":0,"status":"completed",
                 "model":"fixture-model","output":[{"type":"message","id":"msg_fixture",
                 "role":"assistant","status":"completed","phase":"final_answer",
                 "content":[{"type":"output_text","text":"done","annotations":[]}]}],
                 "usage":{"input_tokens":3,"output_tokens":2,"total_tokens":5}}
                """;
            String reply =
                streaming
                    ? event(
                            "response.output_text.delta",
                            "\"item_id\":\"msg_fixture\",\"output_index\":0,\"content_index\":0,\"delta\":\"done\",\"logprobs\":[]")
                        + event("response.completed", "\"response\":" + completed.replace("\n", ""))
                    : completed;
            var body = reply.getBytes(StandardCharsets.UTF_8);
            exchange
                .getResponseHeaders()
                .set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, dripMillis > 0 ? 0 : body.length);
            try (var output = exchange.getResponseBody()) {
              long until =
                  System.nanoTime()
                      + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(dripMillis);
              while (System.nanoTime() < until) {
                output.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
                try {
                  Thread.sleep(40);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new java.io.IOException("Owned fixture interrupted");
                }
              }
              output.write(body);
            } finally {
              exchange.close();
            }
          });
      server.start();
    }

    private String event(String type, String fields) {
      return "event: "
          + type
          + "\ndata: {\"type\":\""
          + type
          + "\",\"sequence_number\":1,"
          + fields
          + "}\n\n";
    }

    RunLlmConnection pin() {
      return new RunLlmConnection(
          "fixture",
          true,
          LlmProvider.OPENAI_COMPATIBLE,
          LlmAuthentication.NONE,
          null,
          null,
          new ResolvedLlmEndpoint(
              "fixture",
              1,
              LlmApiType.RESPONSES,
              "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
              "fixture",
              "fixture-model",
              new LlmCapabilities(ReasoningReplayMode.NONE, 36, Set.of()),
              64,
              null,
              Duration.ofSeconds(5)),
          Map.of(),
          Map.of("max_output_tokens", 64));
    }

    com.openai.client.OpenAIClient client(RunLlmConnection pin) {
      return client(pin, List.of());
    }

    com.openai.client.OpenAIClient client(
        RunLlmConnection pin, List<okhttp3.Interceptor> interceptors) {
      var client =
          PinnedOpenAiClients.open(
              pin,
              () -> {
                throw new AssertionError("No-auth read credentials");
              },
              transport,
              interceptors,
              () -> {});
      clients.add(client);
      return client;
    }

    public void close() {
      clients.forEach(com.openai.client.OpenAIClient::close);
      server.stop(0);
      transport.connectionPool().evictAll();
      transport.dispatcher().executorService().shutdown();
    }
  }
}
