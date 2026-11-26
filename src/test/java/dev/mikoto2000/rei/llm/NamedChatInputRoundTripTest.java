package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.modelmessage.ModelMessageCodec;
import dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/** Production pinned models and canonical storage, using only an owned loopback HTTP fixture. */
class NamedChatInputRoundTripTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nativeReasoningIsRetainedInStorageButNoneReplayStillAllowsTheNextRequest(boolean streaming)
      throws Exception {
    try (var fixture = new Fixture(streaming, true)) {
      var first = new Prompt("fixture", fixture.options);
      ChatResponse response;
      if (streaming) {
        var aggregate = new AtomicReference<ChatResponse>();
        new MessageAggregator()
            .aggregate(fixture.model.stream(first), aggregate::set)
            .blockLast(Duration.ofSeconds(5));
        response = aggregate.get();
      } else response = fixture.model.call(first);
      var codec = new ModelMessageCodec();
      var stored = codec.encode(response.getResult().getOutput());
      assertThat(new String(stored, java.nio.charset.StandardCharsets.UTF_8))
          .contains("PRIVATE_NATIVE_REASONING");
      var restored = codec.decode(stored);
      assertThat(restored).isEqualTo(response.getResult().getOutput());
      var next =
          new Prompt(
              java.util.List.of(
                  new org.springframework.ai.chat.messages.UserMessage("fixture"),
                  restored,
                  new org.springframework.ai.chat.messages.UserMessage("next question")),
              fixture.options);
      if (streaming) fixture.model.stream(next).blockLast(Duration.ofSeconds(5));
      else fixture.model.call(next);
      assertThat(fixture.requests).hasValue(2);
      assertThat(fixture.bodies.get(1).path("messages").toString())
          .contains("done", "next question")
          .doesNotContain("PRIVATE_NATIVE_REASONING", "reasoning_content", "reasoningContent");
      assertThat(codec.encode(restored)).isEqualTo(stored);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nativeAssistantCanBecomeTheNextApprovedInputWithoutLosingItsParts(boolean streaming)
      throws Exception {
    try (var fixture = new Fixture(streaming)) {
      var prompt = new Prompt("fixture", fixture.options);
      ChatResponse result;
      if (streaming) {
        var aggregate = new AtomicReference<ChatResponse>();
        new MessageAggregator()
            .aggregate(fixture.model.stream(prompt), aggregate::set)
            .blockLast(Duration.ofSeconds(5));
        result = aggregate.get();
      } else result = fixture.model.call(prompt);
      assertThat(result.getResult().getOutput().getText()).isEqualTo("done");
      var codec = new ModelMessageCodec();
      var restored = codec.decode(codec.encode(result.getResult().getOutput()));
      assertThat(restored).isEqualTo(result.getResult().getOutput());
      assertThat(fixture.requests).hasValue(1);
    }
  }

  @ParameterizedTest
  @EnumSource(AgentRunContext.Mode.class)
  void normalChatLoopStoresTheRealSdkAssistantInEachMode(AgentRunContext.Mode mode)
      throws Exception {
    try (var fixture = new Fixture(true)) {
      var owner =
          new AgentRunContext(
              "run", "session", directory, "project", AgentRunContext.RequestSource.SHELL, mode);
      var data = new org.sqlite.SQLiteDataSource();
      data.setUrl("jdbc:sqlite:" + directory.resolve("protocol.db"));
      var store = new ModelMessagePayloadStore(data);
      var exchange = store.newExchange(owner);
      var budget = new OutputLimitRunBudget(2, 4);
      assertThat(budget.tryConsumeLlmCall()).isTrue();
      var execution =
          new RunExecutionContext(
              owner.runId(),
              budget,
              new ProgressEvaluator(directory),
              new AgentEventFactory(Clock.systemUTC()),
              ignored -> {});
      execution.setRunContext(owner);
      execution.setContinuation(
          dev.mikoto2000.rei.testfixtures.FixtureInferencePermissions.automatic(data));
      var context = new java.util.HashMap<>(fixture.options.getToolContext());
      context.put(ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY, exchange);
      context.put(
          ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY,
          new ModelMessagePayloadStore.Origin(exchange.ownerKey(), fixture.pin.fingerprint()));
      context.put(RunExecutionContext.KEY, execution);
      var options = fixture.options.mutate().toolContext(context).build();
      execution.setModelOptions(options);
      assertThat(
              new StagnationChatModel(fixture.model)
                  .call(new Prompt("fixture", execution.modelOptions()))
                  .getResult()
                  .getOutput()
                  .getText())
          .isEqualTo("done");
      assertThat(execution.progressSnapshot().protocolHistory()).isNotNull();
      assertThat(fixture.requests).hasValue(1);
    }
  }

  private static final class Fixture implements AutoCloseable {
    final com.sun.net.httpserver.HttpServer server;
    final AtomicInteger requests = new AtomicInteger();
    final java.util.List<com.fasterxml.jackson.databind.JsonNode> bodies =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    final RunLlmConnection pin;
    final OpenAiChatOptions options;
    final PinnedChatModels models;
    final org.springframework.ai.chat.model.ChatModel model;

    Fixture(boolean streaming) throws Exception {
      this(streaming, false);
    }

    Fixture(boolean streaming, boolean reasoning) throws Exception {
      server =
          com.sun.net.httpserver.HttpServer.create(
              new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/v1/chat/completions",
          exchange -> {
            bodies.add(
                new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(exchange.getRequestBody().readAllBytes()));
            requests.incrementAndGet();
            String reply =
                streaming
                    ? """
                    data: {"id":"fixture","object":"chat.completion.chunk","created":0,"model":"fixture-model","choices":[{"index":0,"delta":{"role":"assistant","content":"done"},"finish_reason":null}]}

                    data: {"id":"fixture","object":"chat.completion.chunk","created":0,"model":"fixture-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                    data: [DONE]

                    """
                    : """
                    {"id":"fixture","object":"chat.completion","created":0,"model":"fixture-model","choices":[{"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}
                    """;
            if (reasoning)
              reply =
                  reply.replace(
                      "\"content\":\"done\"",
                      "\"reasoning_content\":\"PRIVATE_NATIVE_REASONING\",\"content\":\"done\"");
            var bytes = reply.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange
                .getResponseHeaders()
                .set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) {
              body.write(bytes);
            } finally {
              exchange.close();
            }
          });
      server.start();
      pin =
          new RunLlmConnection(
              "local",
              true,
              LlmProvider.OPENAI_COMPATIBLE,
              LlmAuthentication.NONE,
              null,
              null,
              new ResolvedLlmEndpoint(
                  "local",
                  1,
                  LlmApiType.CHAT_COMPLETIONS,
                  "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                  "chat-endpoint:local",
                  "fixture-model",
                  new LlmCapabilities(ReasoningReplayMode.NONE, 8, Set.of()),
                  null,
                  null,
                  Duration.ofSeconds(5)),
              Map.of(),
              Map.of());
      var nativeOptions = (OpenAiChatOptions) RunNativeOptions.options(pin);
      options =
          reasoning
              ? nativeOptions
                  .mutate()
                  .toolContext(Map.of(ResponsesReasoningReplay.OWNER_CONTEXT_KEY, "fixture-owner"))
                  .build()
              : nativeOptions;
      models =
          new PinnedChatModels(
              connection -> {
                throw new AssertionError("No credential");
              },
              connection -> {},
              null,
              4);
      model = models.model(pin);
    }

    public void close() {
      models.close();
      server.stop(0);
    }
  }
}
