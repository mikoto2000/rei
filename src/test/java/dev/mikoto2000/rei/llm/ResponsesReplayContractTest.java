package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.chat.ToolLoopSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesMetadata;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Native SDK input/output round trips; no external API or business tool is used. */
@org.junit.jupiter.api.Timeout(30)
class ResponsesReplayContractTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path protocolDirectory;
  private dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore protocolStore;
  private final AtomicInteger transportBuilds = new AtomicInteger();

  private dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore payloads() {
    if (protocolStore == null) {
      var source = new org.sqlite.SQLiteDataSource();
      source.setUrl("jdbc:sqlite:" + protocolDirectory.resolve("protocol.db"));
      protocolStore = new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore(source);
    }
    return protocolStore;
  }

  private Map<String, Object> protocolContext() {
    payloads();
    var context = new java.util.HashMap<String, Object>();
    if (owner != null) context.put(ResponsesReasoningReplay.OWNER_CONTEXT_KEY, owner);
    context.put(
        dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY,
        new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange(
            protocolStore,
            new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope(
                "fixture-project", protocolDirectory.toString(), "fixture-conversation"),
            "fixture-run",
            java.util.UUID.randomUUID().toString()));
    return context;
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
      "503,false,false,true", "429,false,false,true", "408,false,false,true", "409,false,false,true",
      "400,false,false,false", "401,false,false,false", "503,true,false,false",
      "503,false,true,false", "429,false,true,false"})
  void normalizedNativeFailuresRespectTheExistingBoundedRetryAndTokenBudgets(
      int status, boolean tokenLimit, boolean providerDeclinedRetry, boolean expectedRetry) throws Exception {
    retryHint = providerDeclinedRetry ? "false" : null;
    contentType = "text/event-stream";
    httpStatuses.add(status);
    replies.add("{\"error\":{\"message\":\"PRIVATE-retry-fixture\",\"type\":\"fixture\"}}");
    replies.add(sse(done()));
    var budget = new OutputLimitRunBudget(1, 4, null, tokenLimit ? 1000 : 0);
    var owner = new dev.mikoto2000.rei.core.chat.AgentRunContext("fixture-run", "session", protocolDirectory, "project");
    var context = new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(owner.runId(), budget,
        new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(protocolDirectory),
        new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()), ignored -> {});
    context.setRunContext(owner);
    var options = controlledOptions(context, options());
    var reservation = new OutputLimitRunBudget.LlmCallReservation() {
      public boolean tryReserve() { return budget.tryConsumeLlmCall(); }
      public int remaining() { return budget.remainingLlmCalls(); }
      public void recordTotalTokens(Integer count) { budget.recordTotalTokens(count); }
      public boolean tokenLimitEnabled() { return budget.tokenLimitEnabled(); }
      public boolean tokenExhausted() { return budget.tokenExhausted(); }
      public boolean usageUnknown() { return budget.usageUnknown(); }
    };
    var retries = new dev.mikoto2000.rei.core.chat.BoundedToolLoop.ModelRetries(1);
    var result = new dev.mikoto2000.rei.core.chat.BoundedToolLoop().runWithHistory(model(options),
        new Prompt("retry fixture", options), new AtomicInteger(4), owner,
        () -> { context.checkActive(); context.checkModelTokenBudget(); }, reservation, retries);
    if (expectedRetry) assertThat(result.block(Duration.ofSeconds(10)).output()).isEqualTo("done");
    else assertThatThrownBy(() -> result.block(Duration.ofSeconds(10))).isInstanceOf(RuntimeException.class)
        .hasMessageNotContaining("PRIVATE");
    assertThat(requests).hasSize(expectedRetry ? 2 : 1);
    assertThat(budget.snapshot().llmCalls()).isEqualTo(expectedRetry ? 2 : 1);
    assertThat(retries.attempts()).isEqualTo(expectedRetry ? 1 : 0);
    assertThat(executions.get()).isZero();
    if (tokenLimit) assertThat(budget.usageUnknown()).isTrue();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"false,1,1,2,true", "true,1,1,1,false", "false,0,1,1,false", "false,1,2,2,false"})
  void nativeReadTimeoutUsesOnlyTheExistingMainRetryAllowance(boolean tokenLimit, int retryCount,
      int failures, int expectedRequests, boolean success) throws Exception {
    contentType = "text/event-stream";
    timeoutFailures.set(failures);
    replies.add(sse(done()));
    var budget = new OutputLimitRunBudget(1, 4, null, tokenLimit ? 1000 : 0);
    budget.tryConsumeLlmCall();
    var owner = new dev.mikoto2000.rei.core.chat.AgentRunContext("fixture-run", "session", protocolDirectory, "project");
    var context = new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(owner.runId(), budget,
        new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(protocolDirectory),
        new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()), ignored -> {});
    context.setRunContext(owner);
    var defaults = controlledOptions(context, options());
    context.setModelOptions(defaults);
    var options = ((OpenAiResponsesChatOptions) context.modelOptions()).mutate()
        .toolContext(Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY, context)).build();
    var request = new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(model(defaults), null, retryCount)
        .stream(new Prompt("timeout fixture", options)).collectList();
    if (success) {
      var chunks = request.block(Duration.ofSeconds(10));
      assertThat(chunks.getLast().getResult().getOutput().getText()).isEqualTo("done");
      assertThat(chunks.stream().filter(chunk -> ToolLoopSupport.streamRetryAttempt(chunk) > 0).count()).isEqualTo(1);
    } else assertThatThrownBy(() -> request.block(Duration.ofSeconds(10)))
        .isInstanceOf(RuntimeException.class).hasMessageNotContaining("PRIVATE");
    assertThat(requests).hasSize(expectedRequests);
    assertThat(budget.snapshot().llmCalls()).isEqualTo(expectedRequests);
    assertThat(executions.get()).isZero();
    if (tokenLimit) assertThat(budget.usageUnknown()).isTrue();
  }

  @Test
  void configuredNativeModelOptionsAndClientShareOneImmutableEndpointSnapshot() {
    var properties = nativeProperties();
    var provider =
        LlmModelProvider.forResponsesFixture(
            org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class),
            properties,
            httpCustomizers());
    var endpoint = provider.resolvedEndpoint(LlmFeature.CHAT);
    assertThat(endpoint.apiType()).isEqualTo(LlmApiType.RESPONSES);
    assertThat(endpoint.generation()).isEqualTo(7);
    assertThat(endpoint.capabilities().reasoningReplay()).isEqualTo(ReasoningReplayMode.ENCRYPTED);
    assertThat(endpoint.capabilities().maxTools()).isEqualTo(16);
    var client = provider.chatModel(LlmFeature.CHAT);
    var changed = properties.feature(LlmFeature.CHAT);
    changed.setModel("later-model");
    changed.setReasoningReplay(ReasoningReplayMode.NONE);
    changed.setSupportedSettings(java.util.Set.of());
    properties.setFeature(LlmFeature.CHAT, changed);
    var options = provider.requestOptions(LlmFeature.CHAT, "selected-model", true);
    assertThat(options).isInstanceOf(OpenAiResponsesChatOptions.class);
    assertThat(options.getModel()).isEqualTo(endpoint.model());
    assertThat(provider.resolvedEndpoint(LlmFeature.CHAT)).isEqualTo(endpoint);
    assertThat(provider.chatModel(LlmFeature.CHAT)).isSameAs(client);
    var response =
        client.call(
            new Prompt(
                "fixture",
                options
                    .mutate()
                    .toolContext(Map.of(ResponsesReasoningReplay.OWNER_CONTEXT_KEY, owner))
                    .build()));
    assertThat(response.getResult().getOutput().getText()).isEqualTo("done");
    assertThat(requests).hasSize(1);
    assertThat(requests.getFirst().path("model").asText()).isEqualTo("fixture-model");
    assertThat(requests.getFirst().path("temperature").asDouble()).isEqualTo(0.2);
    assertThat(requests.getFirst().path("max_output_tokens").asInt()).isEqualTo(41);
    assertThat(requests.getFirst().path("store").asBoolean(true)).isFalse();
    assertThat(requests.getFirst().has("max_tokens")).isFalse();
    assertThat(requests.getFirst().has("max_completion_tokens")).isFalse();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "ENCRYPTED,false,false,false", "NONE,false,false,false", "ENCRYPTED,true,false,false",
        "NONE,true,false,false",
    "ENCRYPTED,false,true,false", "NONE,false,true,false", "ENCRYPTED,false,true,true",
        "NONE,false,true,true"
  })
  void nativeSubAgentKeepsOnlyItsOwnProtocolContext(
      ReasoningReplayMode replay, boolean foreign, boolean configured, boolean definitionOverride)
      throws Exception {
    contentType = "text/event-stream";
    var output = "{\"status\":\"SUCCESS\",\"summary\":\"done\",\"result\":{},\"warnings\":[]}";
    var definitionModel = definitionOverride ? "child-model" : null;
    var raw = new ObjectMapper().readTree(done());
    ((com.fasterxml.jackson.databind.node.ObjectNode) raw)
        .put("model", definitionModel == null ? "fixture-model" : definitionModel);
    ((com.fasterxml.jackson.databind.node.ObjectNode)
            raw.path("output").get(0).path("content").get(0))
        .put("text", output);
    replies.add(sse(raw.toString()));
    var properties = nativeProperties();
    var server = properties.feature(LlmFeature.CHAT);
    server.setReasoningReplay(replay);
    properties.setFeature(LlmFeature.CHAT, server);
    var provider = nativeProvider(properties);
    var definitions = protocolDirectory.resolve("agents");
    java.nio.file.Files.createDirectories(definitions);
    java.nio.file.Files.writeString(
        definitions.resolve("reviewer.yaml"),
        """
        id: reviewer
        name: Reviewer
        description: Fixture child
        systemPrompt: Inspect the supplied fixture.
        tools: []
        maxSteps: 2
        timeout: 10s
        """
            + (definitionOverride ? "model: child-model\n" : ""));
    var policy = new dev.mikoto2000.rei.subagent.SubAgentToolPolicy(java.util.Set.of());
    var registry =
        new dev.mikoto2000.rei.subagent.SubAgentRegistry(
            definitions,
            new dev.mikoto2000.rei.subagent.SubAgentDefinitionLoader(policy, ignored -> true));
    assertThat(registry.reload()).isEmpty();
    var childOwner =
        new java.util.concurrent.atomic.AtomicReference<
            dev.mikoto2000.rei.core.chat.AgentRunContext>();
    var sentContext = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
    provider.chatModel(LlmFeature.CHAT);
    int acceptedTransportBuilds = transportBuilds.get();
    var transport = provider.subAgentChatModel(definitionModel);
    var model =
        new org.springframework.ai.chat.model.ChatModel() {
          public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return transport.getOptions();
          }

          public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
            throw new AssertionError("stream only");
          }

          public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(
              Prompt prompt) {
            sentContext.set(
                ((org.springframework.ai.model.tool.ToolCallingChatOptions) prompt.getOptions())
                    .getToolContext());
            return transport.stream(prompt);
          }
        };
    var parent =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "parent", "chat", protocolDirectory, "project");
    dev.mikoto2000.rei.subagent.SubAgentRunner runner;
    if (configured) {
      var models = org.mockito.Mockito.spy(provider);
      org.mockito.Mockito.doReturn(model)
          .when(models)
          .subAgentChatModel(org.mockito.ArgumentMatchers.any());
      org.mockito.Mockito.doAnswer(
              invocation -> {
                var owner =
                    (dev.mikoto2000.rei.core.chat.AgentRunContext) invocation.getArgument(3);
                childOwner.set(owner);
                return provider
                    .requestOptions(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.<Boolean>getArgument(2),
                        owner)
                    .mutate()
                    .toolContext(Map.of("parent-only-context", "private parent marker"))
                    .build();
              })
          .when(models)
          .requestOptions(
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.anyBoolean(),
              org.mockito.ArgumentMatchers.any());
      var catalog = org.mockito.Mockito.mock(dev.mikoto2000.rei.subagent.SubAgentToolCatalog.class);
      org.mockito.Mockito.when(catalog.createTools()).thenReturn(List.of());
      var current =
          org.mockito.Mockito.mock(dev.mikoto2000.rei.core.service.ModelHolderService.class);
      org.mockito.Mockito.when(current.get()).thenReturn("selected-model");
      dev.mikoto2000.rei.event.AgentEventPublisher publisher = ignored -> {};
      runner =
          org.springframework.test.util.ReflectionTestUtils.invokeMethod(
              new dev.mikoto2000.rei.subagent.SubAgentConfiguration(),
              "subAgentRunner",
              registry,
              policy,
              catalog,
              models,
              current,
              new dev.mikoto2000.rei.core.service.CommandCancellationService(),
              new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
              publisher,
              java.time.Clock.systemUTC());
    } else {
      runner =
          new dev.mikoto2000.rei.subagent.SubAgentRunner(
              registry,
              policy,
              ignored -> model,
              ignored -> org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build(),
              List::of,
              new dev.mikoto2000.rei.core.service.CommandCancellationService(),
              new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
              ignored -> {},
              java.time.Clock.systemUTC());
      runner.setRunOptionsFactory((selected, owner) -> {
        childOwner.set(owner);
        return provider
            .requestOptions(LlmFeature.CHAT, selected, true, foreign ? parent : owner)
            .mutate()
            .toolContext(Map.of("parent-only-context", "private parent marker"))
            .build();
      });
    }
    dev.mikoto2000.rei.subagent.SubAgentResult result;
    try (var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(parent)) {
      result = runner.run("reviewer", "inspect fixture", null);
    }
    if (foreign) {
      assertThat(result.status())
          .isEqualTo(dev.mikoto2000.rei.subagent.SubAgentResult.Status.FAILED);
      assertThat(requests).isEmpty();
      return;
    }
    assertThat(result.status())
        .as(result.output())
        .isEqualTo(dev.mikoto2000.rei.subagent.SubAgentResult.Status.COMPLETED);
    assertThat(result.structuredOutput().summary()).isEqualTo("done");
    assertThat(requests).hasSize(1);
    assertThat(requests.getFirst().path("model").asText())
        .isEqualTo(definitionModel == null ? "fixture-model" : definitionModel);
    assertThat(
            ((ResolvedLlmEndpoint) sentContext.get().get(ResolvedLlmEndpoint.CONTEXT_KEY)).model())
        .isEqualTo(definitionModel == null ? "fixture-model" : definitionModel);
    assertThat(transportBuilds.get()).isEqualTo(acceptedTransportBuilds);
    assertThat(sentContext.get()).doesNotContainKey("parent-only-context");
    var exchange =
        (dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange)
            sentContext
                .get()
                .get(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY);
    assertThat(exchange.scope())
        .isEqualTo(
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope.forOwner(
                (dev.mikoto2000.rei.core.chat.AgentRunContext)
                    sentContext
                        .get()
                        .get(dev.mikoto2000.rei.core.chat.AgentRunContext.class.getName())));
    assertThat(exchange.run()).isEqualTo(result.subAgentRunId()).isNotEqualTo(parent.runId());
    assertThat(protocolStore.usage(exchange.scope(), exchange.run()).runCumulativeBytes())
        .isPositive();
    assertThat(
            protocolStore
                .usage(
                    dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope.forOwner(parent),
                    parent.runId())
                .runCumulativeBytes())
        .isZero();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"fallback", "model", "url", "replay", "external"})
  void nativeFixturesCannotBypassIncompleteConfigurationOrProductionAvailability(String defect) {
    var properties = nativeProperties();
    var server = properties.feature(LlmFeature.CHAT);
    switch (defect) {
      case "fallback" -> server.setFallbackToDefault(true);
      case "model" -> server.setModel(null);
      case "url" -> server.setBaseUrl(null);
      case "replay" -> server.setReasoningReplay(null);
      case "external" -> server.setBaseUrl("https://api.openai.com");
      default -> throw new AssertionError(defect);
    }
    properties.setFeature(LlmFeature.CHAT, server);
    var provider = nativeProvider(properties);
    assertThatThrownBy(() -> provider.chatModel(LlmFeature.CHAT))
        .isInstanceOf(IllegalStateException.class);
    assertThat(requests).isEmpty();
  }

  @Test
  void explicitlyConfiguredPlaintextFixtureUsesTheAcceptedOwnerAndEndpoint() {
    var properties = nativeProperties();
    var server = properties.feature(LlmFeature.CHAT);
    server.setReasoningReplay(ReasoningReplayMode.PLAINTEXT);
    properties.setFeature(LlmFeature.CHAT, server);
    var provider = nativeProvider(properties);
    var run =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "plain-run", "chat", protocolDirectory, "project");
    var options = provider.requestOptions(LlmFeature.CHAT, null, true, run);
    assertThat(
            provider
                .chatModel(LlmFeature.CHAT)
                .call(new Prompt("fixture", options))
                .getResult()
                .getOutput()
                .getText())
        .isEqualTo("done");
    assertThat(requests).hasSize(1);
    assertThat(requests.getFirst().has("include")).isFalse();
  }

  @Test
  void nativeExplicitSettingsWithoutDeclaredCapabilityFailBeforeHttp() {
    var properties = nativeProperties();
    var server = properties.feature(LlmFeature.CHAT);
    server.setSupportedSettings(java.util.Set.of());
    properties.setFeature(LlmFeature.CHAT, server);
    var provider = nativeProvider(properties);
    var run =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "run", "chat", protocolDirectory, "project");
    assertThatThrownBy(() ->
            provider
                .chatModel(LlmFeature.CHAT)
                .call(
                    new Prompt(
                        "fixture", provider.requestOptions(LlmFeature.CHAT, null, true, run))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("temperature");
    assertThat(requests).isEmpty();
    assertThat(
            protocolStore
                .usage(
                    dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope.forOwner(run),
                    run.runId())
                .payloadCount())
        .isZero();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "ENCRYPTED,compatible", "ENCRYPTED,endpoint", "ENCRYPTED,tools", "ENCRYPTED,instructions",
    "PLAINTEXT,compatible", "PLAINTEXT,endpoint", "PLAINTEXT,tools", "PLAINTEXT,instructions"
  })
  @org.junit.jupiter.api.Timeout(90)
  void nativeCoreCheckpointSurvivesAProcessExitWithoutRepeatingCompletedTools(
      ReasoningReplayMode replay, String change) throws Exception {
    replayMode = replay;
    String first =
        response(
            "resp_checkpoint",
            """
            [{"type":"reasoning","id":"rs_checkpoint","summary":[],"encrypted_content":"restart-opaque","status":"completed"},
             {"type":"function_call","id":"fc_checkpoint","call_id":"call_checkpoint","name":"fixture","arguments":"{}","status":"completed"}]
            """);
    byte[] firstBody = sse(first).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] finalBody = sse(done()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var received =
        new java.util.concurrent.CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/responses",
        exchange -> {
          received.add(new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes()));
          byte[] body = received.size() == 1 ? firstBody : finalBody;
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
      checkpointWorker("write", base);
      checkpointWorker(change.equals("compatible") ? "read" : change, base);
      assertThat(java.nio.file.Files.readAllLines(protocolDirectory.resolve("effects.txt")))
          .containsExactly("fixture-called");
      assertThat(received).hasSize(change.equals("compatible") ? 2 : 1);
      if (change.equals("compatible"))
        assertThat(received.getLast().path("input").toString())
            .contains(
                replay == ReasoningReplayMode.PLAINTEXT
                    ? "PRIVATE-plain-restart-opaque"
                    : "restart-opaque",
                "call_checkpoint",
                "completed fixture result",
                "restart fixture task");
    } finally {
      server.stop(0);
    }
  }

  private void checkpointWorker(String mode, String base) throws Exception {
    var builder =
        new ProcessBuilder(
                dev.mikoto2000.rei.testsupport.JavaFixtureCommand.command(
                    protocolDirectory,
                    NativeCheckpointProcessProbe.class,
                    protocolDirectory.toString(),
                    mode,
                    base,
                    replayMode.name()))
            .redirectErrorStream(true)
            .redirectOutput(protocolDirectory.resolve(mode + "-checkpoint.log").toFile());
    var data = java.nio.file.Files.createDirectories(protocolDirectory.resolve(mode + "-data"));
    builder.environment().put("REI_DATA_DIR", data.toString());
    var process = builder.start();
    if (!process.waitFor(40, java.util.concurrent.TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError(
          "Owned checkpoint fixture JVM timed out: "
              + java.nio.file.Files.readString(
                  protocolDirectory.resolve(mode + "-checkpoint.log")));
    }
    assertThat(process.exitValue())
        .as(
            "checkpoint worker: %s",
            java.nio.file.Files.readString(protocolDirectory.resolve(mode + "-checkpoint.log")))
        .isZero();
  }

  public static class NativeCheckpointProcessProbe {
    public static void main(String[] args) throws Exception {
      var root = java.nio.file.Path.of(args[0]);
      boolean writer = args[1].equals("write");
      boolean changed = !writer && !args[1].equals("read");
      var fixture = new ResponsesReplayContractTest();
      fixture.protocolDirectory = root;
      fixture.replayMode = ReasoningReplayMode.valueOf(args[3]);
      var payloads = fixture.payloads();
      var source = new org.sqlite.SQLiteDataSource();
      source.setUrl("jdbc:sqlite:" + root.resolve("checkpoint.db"));
      var settings = new dev.mikoto2000.rei.checkpoint.CheckpointProperties();
      var repository =
          new dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository(source, settings);
      var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
      beans.addBean("payloads", payloads);
      org.springframework.test.util.ReflectionTestUtils.invokeMethod(
          repository,
          "configureModelMessagePayloads",
          beans.getBeanProvider(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.class));
      var properties = fixture.nativeProperties();
      var server = properties.feature(LlmFeature.CHAT);
      server.setBaseUrl(args[2]);
      server.setMaxOutputTokens(512);
      if (args[1].equals("endpoint")) server.setEndpointGeneration(8);
      properties.setFeature(LlmFeature.CHAT, server);
      var models =
          LlmModelProvider.forResponsesFixture(
              org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class),
              properties,
              List.of());
      models.configureModelMessagePayloads(
          beans.getBeanProvider(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.class));
      var callback =
          new org.springframework.ai.tool.ToolCallback() {
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
              return org.springframework.ai.tool.definition.ToolDefinition.builder()
                  .name("fixture")
                  .description(args[1].equals("tools") ? "changed fixture" : "fixture")
                  .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                  .build();
            }

            public String call(String input) {
              try {
                java.nio.file.Files.writeString(
                    root.resolve("effects.txt"),
                    "fixture-called\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
              } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
              }
              return "completed fixture result";
            }
          };
      var client =
          org.springframework.ai.chat.client.ChatClient.builder(
                  new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(
                      models.chatModel(LlmFeature.CHAT)))
              .defaultSystem(
                  args[1].equals("instructions")
                      ? "changed fixture system"
                      : "fixture stable system")
              .defaultTools(callback)
              .build();
      var holder =
          org.mockito.Mockito.mock(dev.mikoto2000.rei.core.service.ModelHolderService.class);
      org.mockito.Mockito.when(holder.get()).thenReturn("fixture-model");
      var core =
          new dev.mikoto2000.rei.core.chat.ChatExecutionService(
              new FixedLlmChatClientProvider(client),
              holder,
              models,
              new LlmProperties(),
              new dev.mikoto2000.rei.core.service.CommandCancellationService(),
              java.util.Optional.empty(),
              java.util.Optional.empty());
      var logs =
          new dev.mikoto2000.rei.conversation.ConversationLogStore(
              root.resolve("logs"),
              java.time.Clock.systemUTC(),
              new ObjectMapper()
                  .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
      org.springframework.test.util.ReflectionTestUtils.invokeMethod(
          core, "setConversationLogStore", logs);
      org.springframework.test.util.ReflectionTestUtils.invokeMethod(
          core,
          "setConversationTurnStore",
          dev.mikoto2000.rei.conversation.ConversationTurnStore.inMemory());
      var outcome =
          new java.util.concurrent.atomic.AtomicReference<
              dev.mikoto2000.rei.core.chat.ChatExecutionResult>();
      beans.addBean(
          "router",
          new dev.mikoto2000.rei.core.chat.ConversationInputRouter(
              Runnable::run,
              (owner, prompt, input) -> outcome.set(core.execute(owner, prompt, input))));
      try (var checkpoints =
          new dev.mikoto2000.rei.checkpoint.PersistentCheckpointService(
              repository,
              new dev.mikoto2000.rei.checkpoint.CheckpointReconciler(null),
              settings,
              new dev.mikoto2000.rei.event.InMemoryAgentEventBus(),
              beans.getBeanProvider(dev.mikoto2000.rei.core.chat.ConversationInputRouter.class),
              beans.getBeanProvider(dev.mikoto2000.rei.application.run.RunRegistry.class),
              beans.getBeanProvider(dev.mikoto2000.rei.application.run.RunService.class),
              beans.getBeanProvider(dev.mikoto2000.rei.core.actionplan.ActionPlan.class),
              beans.getBeanProvider(dev.mikoto2000.rei.core.working.WorkingSet.class),
              beans.getBeanProvider(dev.mikoto2000.rei.core.taskstate.TaskState.class),
              beans.getBeanProvider(dev.mikoto2000.rei.core.checkpoint.CheckpointStore.class)) {
            @Override
            public void preserveResults(
                dev.mikoto2000.rei.core.chat.AgentRunContext owner,
                List<org.springframework.ai.chat.messages.Message> history,
                dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState progress) {
              super.preserveResults(owner, history, progress);
              if (writer
                  && progress != null
                  && progress.protocolHistory() != null
                  && progress.protocolHistory().entries().stream()
                      .anyMatch(entry -> entry.key().endsWith("/TOOL"))) {
                try {
                  java.nio.file.Files.writeString(
                      root.resolve("task-id.txt"),
                      repository
                          .findByRun(owner.projectId(), owner.runId())
                          .orElseThrow()
                          .taskId());
                } catch (java.io.IOException failure) {
                  throw new IllegalStateException(failure);
                }
                System.exit(0);
              }
            }
          }) {
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            core, "setCheckpoints", checkpoints);
        if (writer) {
          var result =
              core.execute(
                  new dev.mikoto2000.rei.core.chat.AgentRunContext(
                      "before-restart", "fixture-conversation", root, "fixture-project"),
                  "restart fixture task",
                  new dev.mikoto2000.rei.core.chat.UserInterventionQueue());
          throw new AssertionError(
              "Writer failed to exit at its durable tool boundary: " + result.stopCode());
        }
        String task = java.nio.file.Files.readString(root.resolve("task-id.txt"));
        var resumed =
            checkpoints.resume(
                "fixture-project",
                task,
                dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.SHELL);
        assertThat(resumed.previousRunId()).isEqualTo("before-restart");
        assertThat(outcome.get()).isNotNull();
        assertThat(outcome.get().success())
            .as("resumed native Core: %s", outcome.get().stopCode())
            .isEqualTo(!changed);
        if (!changed)
          assertThat(repository.get("fixture-project", task).status()).isEqualTo("COMPLETED");
      }
      System.exit(0);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void oneShotReplyMemoryKeepsVisibleHistoryWithoutReplayingForeignOpaqueParts(
      boolean preseedForeign) throws Exception {
    contentType = "text/event-stream";
    replies.add(sse(reasoningDone()));
    replies.add(sse(done()));
    var properties = nativeProperties();
    properties.setFeature(LlmFeature.BLUESKY_REPLY, nativeProperties().feature(LlmFeature.CHAT));
    var models = nativeProvider(properties);
    var memory = dev.mikoto2000.rei.chatmemory.WindowedConversationMemory.builder().build();
    String conversation = ConversationIds.blueskyReply("fixture.bsky.social");
    if (preseedForeign)
      memory.add(
          conversation,
          org.springframework.ai.chat.messages.AssistantMessage.builder()
              .parts(
                  List.of(
                      new org.springframework.ai.chat.messages.part.ReasoningPart(
                          null,
                          "",
                          new org.springframework.ai.chat.messages.part.OpaquePayload(
                              org.springframework.ai.openai.responses.OpenAiResponsesMetadata
                                  .PROVIDER,
                              org.springframework.ai.openai.responses.OpenAiResponsesMetadata
                                  .ENCRYPTED_CONTENT_KIND,
                              "foreign-private-opaque"),
                          Map.of(
                              org.springframework.ai.openai.responses.OpenAiResponsesMetadata
                                  .ITEM_ID_ATTRIBUTE,
                              "rs_foreign",
                              org.springframework.ai.openai.responses.OpenAiResponsesMetadata
                                  .ITEM_STATUS_ATTRIBUTE,
                              "completed")),
                      new org.springframework.ai.chat.messages.part.TextPart(
                          "foreign visible answer",
                          null,
                          Map.of(
                              org.springframework.ai.openai.responses.OpenAiResponsesMetadata
                                  .ITEM_ID_ATTRIBUTE,
                              "msg_foreign",
                              org.springframework.ai.openai.responses.OpenAiResponsesMetadata
                                  .ITEM_STATUS_ATTRIBUTE,
                              "completed"))))
              .properties(
                  Map.of(
                      "rei.llm.replay-origin-owner",
                      "0".repeat(64),
                      "rei.llm.replay-origin-endpoint",
                      models.resolvedEndpoint(LlmFeature.BLUESKY_REPLY).fingerprint()))
              .build());
    var client =
        org.springframework.ai.chat.client.ChatClient.builder(
                new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(
                    models.chatModel(LlmFeature.BLUESKY_REPLY)))
            .defaultAdvisors(
                new dev.mikoto2000.rei.core.chat.RunScopedAdvisor(
                    dev.mikoto2000.rei.chatmemory.ConversationMemoryAdvisor.builder(memory)
                        .build()))
            .build();
    var holder = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.service.ModelHolderService.class);
    org.mockito.Mockito.when(holder.get()).thenReturn("fixture-model");
    var generator =
        new dev.mikoto2000.rei.bluesky.BlueskyReplyTextGenerator(
            new FixedLlmChatClientProvider(client),
            holder,
            models,
            new dev.mikoto2000.rei.bluesky.BlueskyProperties());
    assertThat(generator.generate("fixture.bsky.social", "first post", List.of()))
        .isEqualTo("done");
    var firstSaved =
        (org.springframework.ai.chat.messages.AssistantMessage) memory.get(conversation).getLast();
    assertThat(firstSaved.getParts())
        .allMatch(org.springframework.ai.chat.messages.part.TextPart.class::isInstance);
    assertThat(firstSaved.getMetadata())
        .doesNotContainKeys("rei.llm.replay-origin-owner", "rei.llm.replay-origin-endpoint");
    assertThat(generator.generate("fixture.bsky.social", "second post", List.of()))
        .isEqualTo("done");
    assertThat(requests).hasSize(2);
    var repeated = requests.getLast().path("input").toString();
    assertThat(repeated)
        .contains("first post", "second post", "done")
        .doesNotContain("opaque-bound", "foreign-private-opaque", "encrypted_content");
    if (preseedForeign) assertThat(repeated).contains("foreign visible answer");
    for (var item : requests.getLast().path("input"))
      assertThat(item.path("type").asText()).isNotEqualTo("reasoning");
  }

  @Test
  void nativeReplanningUsesTheAcceptedMainOutputLimitRatherThanGlobalOrPlannerDefaults()
      throws Exception {
    var truncated =
        done()
            .replaceFirst(
                "\"status\":\"completed\"",
                "\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}");
    replies.add(sse(truncated).replace("response.completed", "response.incomplete"));
    String plan =
        "{\"subgoals\":[{\"id\":\"part\",\"goal\":\"small part\"}],\"finalGoal\":\"integrate\"}";
    replies.add(
        done()
            .replace(
                "\"text\":\"done\"", "\"text\":" + new ObjectMapper().writeValueAsString(plan)));
    replies.add(
        sse(done().replace("resp_done", "resp_subgoal").replace("msg_done", "msg_subgoal")));
    replies.add(sse(done().replace("resp_done", "resp_final").replace("msg_done", "msg_final")));
    var properties = nativeProperties();
    var plannerSettings = nativeProperties().feature(LlmFeature.CHAT);
    plannerSettings.setModel("fixture-planner");
    plannerSettings.setMaxOutputTokens(73);
    properties.setFeature(LlmFeature.OUTPUT_LIMIT_PLANNER, plannerSettings);
    var customizers =
        new java.util.ArrayList<
            org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer>();
    customizers.add(builder ->
        builder.interceptor(chain -> {
          var buffer = new okio.Buffer();
          chain.request().body().writeTo(buffer);
          contentType =
              new ObjectMapper().readTree(buffer.readByteArray()).path("stream").asBoolean()
                  ? "text/event-stream"
                  : "application/json";
          return chain.proceed(chain.request());
        }));
    customizers.addAll(httpCustomizers());
    var models =
        LlmModelProvider.forResponsesFixture(
            org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class),
            properties,
            customizers);
    var beans =
        new org.springframework.beans.factory.support.StaticListableBeanFactory(
            Map.of("payloads", payloads()));
    models.configureModelMessagePayloads(
        beans.getBeanProvider(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.class));
    var holder = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.service.ModelHolderService.class);
    org.mockito.Mockito.when(holder.get()).thenReturn("fixture-model");
    var global = new LlmProperties();
    global.setMaxOutputTokens(2048);
    var planner = new OutputLimitReplanner(models, holder, global);
    var client =
        org.springframework.ai.chat.client.ChatClient.builder(
                new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(
                    models.chatModel(LlmFeature.CHAT)))
            .build();
    var service =
        new dev.mikoto2000.rei.core.chat.ChatExecutionService(
            new FixedLlmChatClientProvider(client),
            holder,
            models,
            global,
            new dev.mikoto2000.rei.core.service.CommandCancellationService(),
            java.util.Optional.empty(),
            java.util.Optional.of(planner));
    var logs =
        new dev.mikoto2000.rei.conversation.ConversationLogStore(
            protocolDirectory.resolve("logs"),
            java.time.Clock.systemUTC(),
            new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
    org.springframework.test.util.ReflectionTestUtils.invokeMethod(
        service, "setConversationLogStore", logs);
    org.springframework.test.util.ReflectionTestUtils.invokeMethod(
        service,
        "setConversationTurnStore",
        dev.mikoto2000.rei.conversation.ConversationTurnStore.inMemory());
    var run =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "fixture-run", "fixture-conversation", protocolDirectory, "fixture-project");
    var result =
        service.execute(
            run, "fixture request", new dev.mikoto2000.rei.core.chat.UserInterventionQueue());
    assertThat(result.success()).as("native replan: %s", result.stopCode()).isTrue();
    assertThat(requests).hasSize(4);
    assertThat(requests.get(1).path("max_output_tokens").asInt()).isEqualTo(73);
    String plannerInput = requests.get(1).path("input").toString();
    assertThat(plannerInput).contains("Max output tokens per call:\\n41");
    for (int index : List.of(0, 2, 3))
      assertThat(requests.get(index).path("max_output_tokens").asInt()).isEqualTo(41);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = ReasoningReplayMode.class,
      names = {"ENCRYPTED", "NONE"})
  void acceptedNativeOwnersStayDistinctAcrossRunsAndCredentialRotation(ReasoningReplayMode replay) {
    var properties = nativeProperties();
    var server = properties.feature(LlmFeature.CHAT);
    server.setReasoningReplay(replay);
    properties.setFeature(LlmFeature.CHAT, server);
    var provider = nativeProvider(properties);
    var parent =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "parent", "chat", protocolDirectory, "project");
    var child =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "child", "subagent:child", protocolDirectory, "project");
    var parentOptions = provider.requestOptions(LlmFeature.CHAT, null, true, parent);
    var childOptions = provider.requestOptions(LlmFeature.CHAT, null, true, child);
    var parentExchange =
        (dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange)
            parentOptions
                .getToolContext()
                .get(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY);
    var childExchange =
        (dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange)
            childOptions
                .getToolContext()
                .get(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY);
    assertThat(parentExchange.scope())
        .isEqualTo(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope.forOwner(parent));
    assertThat(childExchange.scope())
        .isEqualTo(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope.forOwner(child));
    assertThat(parentExchange.ownerKey()).isNotEqualTo(childExchange.ownerKey());
    var again = provider.requestOptions(LlmFeature.CHAT, null, true, parent);
    var againExchange =
        (dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange)
            again
                .getToolContext()
                .get(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY);
    assertThat(againExchange.ownerKey()).isEqualTo(parentExchange.ownerKey());
    assertThat(againExchange.key()).isNotEqualTo(parentExchange.key());
    replies.add(replay == ReasoningReplayMode.ENCRYPTED ? reasoningDone() : done());
    var first =
        provider
            .chatModel(LlmFeature.CHAT)
            .call(new Prompt("fixture", parentOptions))
            .getResult()
            .getOutput();
    var ref = parentExchange.reference(first);
    var saved =
        protocolStore.load(
            parentExchange.scope(),
            ref,
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.RUN,
            parent.runId());
    assertThatThrownBy(() -> protocolStore.load(childExchange.scope(), ref))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() ->
            provider
                .chatModel(LlmFeature.CHAT)
                .call(new Prompt(List.of(saved, new UserMessage("continue")), childOptions)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("owner");
    assertThat(requests).hasSize(1);
    provider
        .chatModel(LlmFeature.CHAT)
        .call(new Prompt(List.of(saved, new UserMessage("continue")), again));
    server.setApiKey("rotated-fixture-key");
    properties.setFeature(LlmFeature.CHAT, server);
    var rotated = nativeProvider(properties);
    assertThat(rotated.resolvedEndpoint(LlmFeature.CHAT).fingerprint())
        .isEqualTo(provider.resolvedEndpoint(LlmFeature.CHAT).fingerprint());
    rotated
        .chatModel(LlmFeature.CHAT)
        .call(
            new Prompt(
                List.of(saved, new UserMessage("continue")),
                rotated.requestOptions(LlmFeature.CHAT, null, true, parent)));
    assertThat(requests).hasSize(3);
    server.setEndpointGeneration(8);
    properties.setFeature(LlmFeature.CHAT, server);
    var changed = nativeProvider(properties);
    assertThatThrownBy(() ->
            changed
                .chatModel(LlmFeature.CHAT)
                .call(
                    new Prompt(
                        List.of(saved, new UserMessage("continue")),
                        changed.requestOptions(LlmFeature.CHAT, null, true, parent))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("endpoint");
    assertThat(requests).hasSize(3);
    assertThat(protocolStore.usage(parentExchange.scope(), parent.runId()).runCumulativeBytes())
        .isPositive();
    assertThat(protocolStore.usage(childExchange.scope(), child.runId()).runCumulativeBytes())
        .isZero();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = ReasoningReplayMode.class,
      names = {"ENCRYPTED", "NONE"})
  void completedHistoryReplayRequiresExactStoredMessagesAndTheTargetRun(ReasoningReplayMode replay)
      throws Exception {
    var properties = nativeProperties();
    var server = properties.feature(LlmFeature.CHAT);
    server.setReasoningReplay(replay);
    properties.setFeature(LlmFeature.CHAT, server);
    var models = nativeProvider(properties);
    var previous =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "previous", "chat", protocolDirectory, "project");
    var previousOptions = models.requestOptions(LlmFeature.CHAT, null, true, previous);
    var previousExchange =
        (dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange)
            previousOptions
                .getToolContext()
                .get(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY);
    var completed =
        reasoningDone()
            .replace(
                "{\"type\":\"reasoning\",\"id\":\"rs_bound\"",
                "{\"type\":\"reasoning\",\"id\":\"rs_extra\",\"summary\":[],\"encrypted_content\":\"opaque-extra+/=\",\"status\":\"completed\"},{\"type\":\"reasoning\",\"id\":\"rs_bound\"");
    replies.add(replay == ReasoningReplayMode.ENCRYPTED ? completed : done());
    var first =
        models
            .chatModel(LlmFeature.CHAT)
            .call(new Prompt("first", previousOptions))
            .getResult()
            .getOutput();
    var reference = previousExchange.reference(first);
    var pointer =
        protocolStore.historyReference(previousExchange.scope(), reference, "completed:previous");
    var current =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "current", "chat", protocolDirectory, "project");
    var currentOptions = models.requestOptions(LlmFeature.CHAT, null, true, current);
    var currentExchange =
        (dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange)
            currentOptions
                .getToolContext()
                .get(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY);
    var origin =
        (dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Origin)
            currentOptions
                .getToolContext()
                .get(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY);
    var grant = protocolStore.historyReplayGrant(currentExchange, List.of(pointer), origin);
    var granted =
        currentOptions
            .mutate()
            .toolContext(
                Map.of(
                    dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore
                        .HISTORY_GRANT_CONTEXT_KEY,
                    grant))
            .build();
    var historical =
        dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor.historical(first, 4);
    var response =
        models
            .chatModel(LlmFeature.CHAT)
            .call(new Prompt(List.of(historical, new UserMessage("second")), granted));
    assertThat(response.getResult().getOutput().getText()).isEqualTo("done");
    assertThat(requests).hasSize(2);
    assertThat(first.getMetadata())
        .containsEntry(ResponsesReasoningReplay.ORIGIN_OWNER, previousExchange.ownerKey());
    if (replay == ReasoningReplayMode.ENCRYPTED)
      assertThat(requests.getLast().path("input").toString())
          .contains("opaque-extra+/=", "opaque-bound");
    assertThat(grant.toString()).doesNotContain("opaque-extra", "opaque-bound");

    var changedParts = new java.util.ArrayList<>(first.getParts());
    changedParts.add(org.springframework.ai.chat.messages.part.TextPart.of("unapproved addition"));
    var changed =
        AssistantMessage.builder().parts(changedParts).properties(first.getMetadata()).build();
    assertThatThrownBy(() ->
            models
                .chatModel(LlmFeature.CHAT)
                .call(new Prompt(List.of(changed, new UserMessage("second")), granted)))
        .isInstanceOf(IllegalArgumentException.class);
    var other =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "other", "chat", protocolDirectory, "project");
    var wrongTarget =
        models
            .requestOptions(LlmFeature.CHAT, null, true, other)
            .mutate()
            .toolContext(
                Map.of(
                    dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore
                        .HISTORY_GRANT_CONTEXT_KEY,
                    grant))
            .build();
    assertThatThrownBy(() ->
            models
                .chatModel(LlmFeature.CHAT)
                .call(new Prompt(List.of(historical, new UserMessage("second")), wrongTarget)))
        .isInstanceOf(IllegalArgumentException.class);
    var unpublished = previousExchange.withKey("unpublished").reference(first);
    var runOnlyPointer =
        Map.of(
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.HISTORY_ID,
            unpublished.id(),
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.HISTORY_HASH,
            unpublished.sha256(),
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.HISTORY_OWNER,
            "completed:previous");
    assertThatThrownBy(() ->
            protocolStore.historyReplayGrant(currentExchange, List.of(runOnlyPointer), origin))
        .isInstanceOf(IllegalStateException.class);
    var child =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "child", "child-chat", protocolDirectory, "project");
    var childOptions = models.requestOptions(LlmFeature.CHAT, null, true, child);
    var childExchange =
        (dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange)
            childOptions
                .getToolContext()
                .get(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY);
    var childOrigin =
        (dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Origin)
            childOptions
                .getToolContext()
                .get(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY);
    assertThatThrownBy(
            () -> protocolStore.historyReplayGrant(childExchange, List.of(pointer), childOrigin))
        .isInstanceOf(IllegalStateException.class);
    var incompatible =
        new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Origin(
            origin.owner(), "different-endpoint");
    assertThatThrownBy(
            () -> protocolStore.historyReplayGrant(currentExchange, List.of(pointer), incompatible))
        .isInstanceOf(IllegalStateException.class);
    assertThat(requests).hasSize(2);
    protocolStore.release(
        previousExchange.scope(),
        reference,
        dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.HISTORY,
        "completed:previous");
    protocolStore.release(
        previousExchange.scope(),
        reference,
        dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.RUN,
        previous.runId());
    try (var db =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + protocolDirectory.resolve("protocol.db"));
        var statement = db.createStatement()) {
      statement.executeUpdate("UPDATE model_message_payloads SET staged_until=0");
    }
    assertThat(protocolStore.collectExpired())
        .as("accepted target Run owns a lease on borrowed history")
        .isZero();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "ENCRYPTED,false",
    "ENCRYPTED,true",
    "PLAINTEXT,false",
    "PLAINTEXT,true"
  })
  void approvalRestartRestoresBorrowedReplayAuthorityBeforeRemainingCallbacks(
      ReasoningReplayMode replay, boolean endpointChanged) throws Exception {
    replayMode = replay;
    var properties = nativeProperties();
    var models = nativeProvider(properties);
    var previous =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "previous", "chat", protocolDirectory, "project");
    var current =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "current", "chat", protocolDirectory, "project");
    var previousOptions = models.requestOptions(LlmFeature.CHAT, null, true, previous);
    replies.add(reasoningDone());
    var original =
        models
            .chatModel(LlmFeature.CHAT)
            .call(new Prompt("first", previousOptions))
            .getResult()
            .getOutput();
    var previousExchange =
        (dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange)
            previousOptions
                .getToolContext()
                .get(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY);
    var originalRef = previousExchange.reference(original);
    var pointer =
        protocolStore.historyReference(previousExchange.scope(), originalRef, "history:previous");
    var callbacks = options().getToolCallbacks();
    var currentOptions =
        models
            .requestOptions(LlmFeature.CHAT, null, true, current)
            .mutate()
            .toolCallbacks(callbacks)
            .build();
    var currentExchange =
        (dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange)
            currentOptions
                .getToolContext()
                .get(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY);
    var currentInput = new UserMessage("current request");
    var currentInputRef = currentExchange.withKey("current-input").reference(currentInput);
    var origin =
        (dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Origin)
            currentOptions
                .getToolContext()
                .get(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY);
    var grant = protocolStore.historyReplayGrant(currentExchange, List.of(pointer), origin);
    var granted =
        currentOptions
            .mutate()
            .toolContext(
                Map.of(
                    dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore
                        .HISTORY_GRANT_CONTEXT_KEY,
                    grant))
            .build();
    replies.add(toolResponse());
    var pendingMessage =
        models
            .chatModel(LlmFeature.CHAT)
            .call(new Prompt(List.of(original, new UserMessage("current request")), granted))
            .getResult()
            .getOutput();
    var pendingRef = currentExchange.reference(pendingMessage);
    var firstCall = pendingMessage.getToolCalls().getFirst();
    String firstResult = callbacks.getFirst().call(firstCall.arguments());
    var completed =
        new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
            firstCall.id(), firstCall.name(), firstResult);
    var prefixRef =
        currentExchange
            .withKey(currentExchange.key() + "/prefix:1")
            .reference(
                org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                    .responses(List.of(completed))
                    .build());
    var pending =
        new dev.mikoto2000.rei.core.chat.RunContinuation.PendingTools(
            pendingMessage.getText(),
            pendingMessage.getToolCalls(),
            List.of(completed),
            java.util.Set.of(),
            pendingRef,
            prefixRef);
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + protocolDirectory.resolve("protocol.db"));
    var approvals = new dev.mikoto2000.rei.core.policy.ApprovalContinuationStore(source);
    var budget = new OutputLimitRunBudget(2, 8);
    budget.tryConsumeLlmCall();
    budget.tryConsumeLlmCall();
    var systemRef =
        currentExchange
            .withKey("instructions")
            .reference(
                new org.springframework.ai.chat.messages.SystemMessage(
                    "original approval instructions"));
    var ownHistory =
        new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.History(
            1,
            current.runId(),
            origin,
            List.of(
                new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.History.Entry(
                    "current-input/USER", currentInputRef),
                new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.History.Entry(
                    currentExchange.key() + "/ASSISTANT", pendingRef),
                new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.History.Entry(
                    currentExchange.key() + "/TOOL", prefixRef)),
            new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.History.Input(
                List.of(
                    new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.History.Entry(
                        "instructions/SYSTEM", systemRef),
                    new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.History.Entry(
                        "prior/ASSISTANT", originalRef),
                    new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.History.Entry(
                        "current-input/USER", currentInputRef)),
                1));
    approvals.save(
        "approval",
        current,
        "current request",
        List.of(
            dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor.historical(original, 4),
            currentInput),
        pending,
        budget.snapshot(),
        false,
        ownHistory);
    for (var kind :
        java.util.List.of(
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.HISTORY,
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.RUN))
      protocolStore.release(
          previousExchange.scope(),
          originalRef,
          kind,
          kind == dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.HISTORY
              ? "history:previous"
              : previous.runId());
    protocolStore.release(
        previousExchange.scope(),
        originalRef,
        dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.RUN,
        current.runId());
    protocolStore.release(
        currentExchange.scope(),
        systemRef,
        dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.RUN,
        current.runId());
    var restarted = new dev.mikoto2000.rei.core.policy.ApprovalContinuationStore(source);
    var saved = restarted.saved(restarted.find("approval").orElseThrow());
    assertThat(saved.protocolHistory())
        .as("own input and protocol trace survive approval storage restart")
        .isEqualTo(ownHistory);
    var execution =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(
            current.runId(),
            new OutputLimitRunBudget(2, 8),
            new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(protocolDirectory),
            new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
            ignored -> {});
    execution.setRunContext(current);
    execution.resume(saved);
    if (endpointChanged) {
      var server = properties.feature(LlmFeature.CHAT);
      server.setEndpointGeneration(8);
      properties.setFeature(LlmFeature.CHAT, server);
      var changed = nativeProvider(properties);
      assertThatThrownBy(() ->
              execution.setModelOptions(
                  changed.requestOptions(LlmFeature.CHAT, null, true, current)))
          .isInstanceOf(IllegalStateException.class);
      assertThat(executions).hasValue(1);
      assertThat(requests).hasSize(2);
      return;
    }
    execution.setModelOptions(
        models
            .requestOptions(LlmFeature.CHAT, null, true, current)
            .mutate()
            .toolCallbacks(callbacks)
            .build());
    assertThat(execution.progressSnapshot().protocolHistory()).isEqualTo(ownHistory);
    assertThat(
            protocolStore
                .load(
                    currentExchange.scope(),
                    systemRef,
                    dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.RUN,
                    current.runId())
                .getText())
        .as("restore all input leases before approval callbacks")
        .isEqualTo("original approval instructions");
    assertThat(
            execution
                .modelOptions()
                .getToolContext()
                .get(
                    dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore
                        .HISTORY_GRANT_CONTEXT_KEY))
        .as("restore verified replay authority before remaining callback dispatch")
        .isNotNull();
    var resumed =
        new ToolLoopSupport()
            .executePending(
                new Prompt(saved.messages(), execution.modelOptions()),
                saved.pendingTools(),
                execution);
    assertThat(executions)
        .as("reuse the first successful prefix and execute only the remaining call")
        .hasValue(2);
    assertThat(execution.protocolHistorySnapshot().entries())
        .as("reuse original iteration across approval restart")
        .hasSize(3)
        .element(1)
        .isEqualTo(ownHistory.entries().get(1));
    var result =
        models
            .chatModel(LlmFeature.CHAT)
            .call(new Prompt(resumed.conversationHistory(), execution.modelOptions()));
    assertThat(result.getResult().getOutput().getText()).isEqualTo("done");
    assertThat(requests).hasSize(3);
    assertThat(requests.getLast().path("input").toString())
        .contains("opaque-bound", "opaque-one+/=", "opaque-two+/=");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"compatible", "temperature", "instructions", "tools", "context-data"})
  void checkpointConsumerKeepsTheOriginalSystemPrefixAndRejectsExecutionChanges(String change)
      throws Exception {
    contentType = "text/event-stream";
    replies.add(sse(done()));
    replies.add(sse(done()));
    var models = nativeProvider(nativeProperties());
    var source =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "contract-source", "chat", protocolDirectory, "project");
    var execution =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(
            source.runId(),
            new OutputLimitRunBudget(2, 8),
            new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(protocolDirectory),
            new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
            ignored -> {});
    execution.setRunContext(source);
    var options = models.requestOptions(LlmFeature.CHAT, null, true, source);
    var context = new java.util.HashMap<>(options.getToolContext());
    context.put(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY, execution);
    execution.setModelOptions(options.mutate().toolContext(null).toolContext(context).build());
    execution.preserveUserMessage(new UserMessage("original request"));
    var originalInput = new java.util.ArrayList<org.springframework.ai.chat.messages.Message>();
    originalInput.add(
        new org.springframework.ai.chat.messages.SystemMessage("original instructions"));
    if (change.equals("context-data"))
      originalInput.add(
          org.springframework.ai.chat.messages.SystemMessage.builder()
              .text("previous work context")
              .metadata(Map.of("rei.workContext", true))
              .build());
    originalInput.add(new UserMessage("original request"));
    new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(models.chatModel(LlmFeature.CHAT))
        .stream(new Prompt(originalInput, execution.modelOptions()))
            .blockLast(Duration.ofSeconds(10));
    var history = execution.protocolHistorySnapshot();
    String checkpoint =
        dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.checkpointOwner(
            "project", "task", 1);
    var scope = dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope.forOwner(source);
    protocolStore.protectCheckpoint(scope, history, checkpoint);
    var references =
        history.protectedEntries().stream()
            .map(entry ->
                new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReplayReference(
                    entry.reference(),
                    dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind
                        .CHECKPOINT,
                    checkpoint))
            .toList();
    var target =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "contract-target", "chat", protocolDirectory, "project");
    var restored =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(
            target.runId(),
            new OutputLimitRunBudget(2, 8),
            new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(protocolDirectory),
            new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
            ignored -> {});
    restored.setRunContext(target);
    var targetOptions = models.requestOptions(LlmFeature.CHAT, null, true, target);
    if (change.equals("temperature"))
      targetOptions = targetOptions.mutate().temperature(0.3).build();
    if (change.equals("tools"))
      targetOptions = targetOptions.mutate().toolCallbacks(options().getToolCallbacks()).build();
    var targetContext = new java.util.HashMap<>(targetOptions.getToolContext());
    targetContext.put(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY, restored);
    restored.setModelOptions(
        targetOptions.mutate().toolContext(null).toolContext(targetContext).build());
    restored.restoreProgress(execution.progressSnapshot(), references);
    var model =
        new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(
            models.chatModel(LlmFeature.CHAT));
    var currentInput = new java.util.ArrayList<org.springframework.ai.chat.messages.Message>();
    currentInput.add(
        new org.springframework.ai.chat.messages.SystemMessage(
            change.equals("instructions") ? "changed instructions" : "original instructions"));
    if (change.equals("context-data"))
      currentInput.add(
          org.springframework.ai.chat.messages.SystemMessage.builder()
              .text("updated work context")
              .metadata(Map.of("rei.workContext", true))
              .build());
    currentInput.add(new UserMessage("host resume control"));
    var prompt = new Prompt(currentInput, restored.modelOptions());
    if (!java.util.Set.of("compatible", "context-data").contains(change)) {
      assertThatThrownBy(() -> model.stream(prompt).blockLast(Duration.ofSeconds(10)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("continuation");
      assertThat(requests).hasSize(1);
      assertThat(executions).hasValue(0);
      return;
    }
    model.stream(prompt).blockLast(Duration.ofSeconds(10));
    assertThat(requests).hasSize(2);
    assertThat(requests.getLast().path("instructions").asText()).isEqualTo("original instructions");
    if (change.equals("context-data")) {
      assertThat(requests.getLast().path("input").toString())
          .contains("previous work context", "updated work context");
      for (String expectedContext : List.of("previous work context", "updated work context"))
        assertThat(
                java.util.stream.StreamSupport.stream(
                        requests.getLast().path("input").spliterator(), false)
                    .filter(item ->
                        "developer".equals(item.path("role").asText())
                            && item.path("content").toString().contains(expectedContext))
                    .count())
            .isEqualTo(1);
    }
    assertThat(requests.getLast().path("input").toString())
        .contains("original request")
        .doesNotContain("host resume control");
    assertThat(
            restored.protocolHistorySnapshot().input().entries().stream()
                .map(entry -> protocolStore.load(scope, entry.reference()))
                .filter(message ->
                    message instanceof org.springframework.ai.chat.messages.SystemMessage
                        && "original instructions".equals(message.getText()))
                .count())
        .as("do not accumulate duplicate base instructions inside the saved input")
        .isEqualTo(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void inputCheckpointFailureDoesNotSendOrInventUnknownProviderUsage(boolean assembled)
      throws Exception {
    var models = nativeProvider(nativeProperties());
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "input-failure", "chat", protocolDirectory, "project");
    var budget = new OutputLimitRunBudget(2, 8, null, 1000);
    budget.tryConsumeLlmCall();
    var execution =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(
            owner.runId(),
            budget,
            new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(protocolDirectory),
            new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
            ignored -> {});
    execution.setRunContext(owner);
    var options = models.requestOptions(LlmFeature.CHAT, null, true, owner);
    var values = new java.util.HashMap<>(options.getToolContext());
    values.put(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY, execution);
    execution.setModelOptions(options.mutate().toolContext(null).toolContext(values).build());
    execution.preserveUserMessage(new UserMessage("original input"));
    execution.setToolResultsCheckpoint(ignored -> {
      throw new IllegalStateException("input checkpoint unavailable");
    });
    dev.mikoto2000.rei.core.contextbudget.ContextAssembler assembler = null;
    if (assembled) {
      assembler =
          org.mockito.Mockito.mock(dev.mikoto2000.rei.core.contextbudget.ContextAssembler.class);
      org.mockito.Mockito.when(
              assembler.assemble(
                  org.mockito.ArgumentMatchers.any(),
                  org.mockito.ArgumentMatchers.anyString(),
                  org.mockito.ArgumentMatchers.anyString(),
                  org.mockito.ArgumentMatchers.any()))
          .thenAnswer(call -> call.getArgument(0));
      org.mockito.Mockito.when(assembler.reviewContinuation(org.mockito.ArgumentMatchers.any()))
          .thenReturn("");
    }
    var controlled =
        new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(
            models.chatModel(LlmFeature.CHAT), assembler);
    var failure =
        org.assertj.core.api.Assertions.catchThrowable(() ->
            controlled.stream(new Prompt("original input", execution.modelOptions()))
                .blockLast(Duration.ofSeconds(10)));
    assertThat(requests).isEmpty();
    assertThat(executions).hasValue(0);
    assertThat(budget.snapshot().usageUnknown())
        .as("no transport started, so usage is not unknown")
        .isFalse();
    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("input checkpoint unavailable");
    assertThat(execution.protocolHistorySnapshot().input()).isNull();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void actualNativeSendPersistsItsCompleteInputBeforeHttp(boolean assembled) throws Exception {
    contentType = "text/event-stream";
    replies.add(sse(done()));
    var models = nativeProvider(nativeProperties());
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "snapshot", "chat", protocolDirectory, "project");
    var budget = new OutputLimitRunBudget(2, 8);
    budget.tryConsumeLlmCall();
    var execution =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(
            owner.runId(),
            budget,
            new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(protocolDirectory),
            new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
            ignored -> {});
    execution.setRunContext(owner);
    var options = models.requestOptions(LlmFeature.CHAT, null, true, owner);
    var values = new java.util.HashMap<>(options.getToolContext());
    values.put(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY, execution);
    execution.setModelOptions(options.mutate().toolContext(null).toolContext(values).build());
    var user = new org.springframework.ai.chat.messages.UserMessage("original input");
    execution.preserveUserMessage(user);
    var capturedBeforeHttp = new java.util.concurrent.atomic.AtomicBoolean();
    execution.setToolResultsCheckpoint(ignored -> {
      var history = execution.protocolHistorySnapshot();
      if (history != null
          && history.input() != null
          && capturedBeforeHttp.compareAndSet(false, true))
        assertThat(requests).as("publish full input before any HTTP side effect").isEmpty();
    });
    dev.mikoto2000.rei.core.contextbudget.ContextAssembler assembler = null;
    if (assembled) {
      assembler =
          org.mockito.Mockito.mock(dev.mikoto2000.rei.core.contextbudget.ContextAssembler.class);
      org.mockito.Mockito.when(
              assembler.assemble(
                  org.mockito.ArgumentMatchers.any(),
                  org.mockito.ArgumentMatchers.anyString(),
                  org.mockito.ArgumentMatchers.anyString(),
                  org.mockito.ArgumentMatchers.any()))
          .thenAnswer(call -> call.getArgument(0));
      org.mockito.Mockito.when(assembler.reviewContinuation(org.mockito.ArgumentMatchers.any()))
          .thenReturn("");
    }
    new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(
            models.chatModel(LlmFeature.CHAT), assembler)
        .stream(
                new Prompt(
                    List.of(
                        new org.springframework.ai.chat.messages.SystemMessage(
                            "original instructions"),
                        user),
                    execution.modelOptions()))
            .blockLast();
    assertThat(capturedBeforeHttp).isTrue();
    var history = execution.protocolHistorySnapshot();
    assertThat(history.input()).isNotNull();
    var exchange =
        (dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange)
            execution
                .modelOptions()
                .getToolContext()
                .get(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY);
    String checkpoint =
        dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.checkpointOwner(
            "project", "task", 1);
    protocolStore.protectCheckpoint(exchange.scope(), history, checkpoint);
    var references =
        history.protectedEntries().stream()
            .map(entry ->
                new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReplayReference(
                    entry.reference(),
                    dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind
                        .CHECKPOINT,
                    checkpoint))
            .toList();
    var next =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "resumed-snapshot", "chat", protocolDirectory, "project");
    var restored =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(
            next.runId(),
            new OutputLimitRunBudget(2, 8),
            new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(protocolDirectory),
            new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
            ignored -> {});
    restored.setRunContext(next);
    restored.setModelOptions(models.requestOptions(LlmFeature.CHAT, null, true, next));
    restored.restoreProgress(execution.progressSnapshot(), references);
    assertThat(restored.takeResumeMessages())
        .extracting(org.springframework.ai.chat.messages.Message::getText)
        .containsExactly("original instructions", "original input", "done");
    assertThat(requests).hasSize(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void completedHistoryFlowsThroughRealCoreAdvisorsAndTheNativeSdk(boolean compatible)
      throws Exception {
    contentType = "text/event-stream";
    replies.add(sse(reasoningDone()));
    replies.add(sse(done()));
    var turns = dev.mikoto2000.rei.conversation.ConversationTurnStore.inMemory();
    var logs =
        new dev.mikoto2000.rei.conversation.ConversationLogStore(
            protocolDirectory.resolve("logs"),
            java.time.Clock.systemUTC(),
            new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
    var summaries =
        new dev.mikoto2000.rei.core.contextbudget.ConversationSummaryRepository(protocolDirectory);
    var memory = dev.mikoto2000.rei.chatmemory.WindowedConversationMemory.builder().build();
    for (int i = 0; i < 2; i++) {
      var properties = nativeProperties();
      if (i == 1 && !compatible) {
        var server = properties.feature(LlmFeature.CHAT);
        server.setEndpointGeneration(8);
        properties.setFeature(LlmFeature.CHAT, server);
      }
      var models = nativeProvider(properties);
      var client =
          org.springframework.ai.chat.client.ChatClient.builder(
                  new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(
                      models.chatModel(LlmFeature.CHAT)))
              .defaultAdvisors(
                  new dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor(
                      turns, memory, summaries, logs, () -> protocolStore))
              .build();
      var clients = new FixedLlmChatClientProvider(client);
      var holder =
          org.mockito.Mockito.mock(dev.mikoto2000.rei.core.service.ModelHolderService.class);
      org.mockito.Mockito.when(holder.get()).thenReturn("fixture-model");
      var service =
          new dev.mikoto2000.rei.core.chat.ChatExecutionService(
              clients,
              holder,
              models,
              new LlmProperties(),
              new dev.mikoto2000.rei.core.service.CommandCancellationService(),
              java.util.Optional.empty(),
              java.util.Optional.empty());
      org.springframework.test.util.ReflectionTestUtils.invokeMethod(
          service, "setConversationLogStore", logs);
      org.springframework.test.util.ReflectionTestUtils.invokeMethod(
          service, "setConversationTurnStore", turns);
      var run =
          new dev.mikoto2000.rei.core.chat.AgentRunContext(
              i == 0 ? "first" : "second",
              "fixture-conversation",
              protocolDirectory,
              "fixture-project");
      var result =
          service.execute(
              run,
              i == 0 ? "first request" : "second request",
              new dev.mikoto2000.rei.core.chat.UserInterventionQueue());
      assertThat(result.success()).as("native Run %s: %s", run.runId(), result.stopCode()).isTrue();
    }
    assertThat(requests).hasSize(2);
    var input = requests.getLast().path("input");
    assertThat(input.toString()).contains("first request", "second request", "done");
    if (compatible) {
      assertThat(input.toString()).contains("opaque-bound");
      assertThat(
              java.util.stream.StreamSupport.stream(input.spliterator(), false)
                  .filter(item -> "reasoning".equals(item.path("type").asText()))
                  .count())
          .isEqualTo(1);
    } else assertThat(input.toString()).doesNotContain("opaque-bound", "encrypted_content");
    assertThat(turns.read("fixture-conversation"))
        .allMatch(dev.mikoto2000.rei.conversation.ConversationTurnStore.Turn::completed);
    assertThat(logs.readAll())
        .extracting(dev.mikoto2000.rei.conversation.ConversationLogEntry::content)
        .containsExactly("first request", "done", "second request", "done");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.class)
  void mainChatExecutionUsesTheAcceptedNativeOptionsWithoutChatCasts(
      dev.mikoto2000.rei.core.chat.AgentRunContext.Mode mode) throws Exception {
    completedNativeRun(mode, false);
  }

  @Test
  void leaseCleanupFailureCannotChangeAnAlreadyDurableSuccessfulRun() throws Exception {
    protocolStore = org.mockito.Mockito.spy(payloads());
    org.mockito.Mockito.doThrow(new IllegalStateException("private retention database path"))
        .when(protocolStore)
        .releaseRun(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("fixture-run"));
    completedNativeRun(dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE, true);
  }

  private void completedNativeRun(
      dev.mikoto2000.rei.core.chat.AgentRunContext.Mode mode, boolean retainedAfterCleanupFailure)
      throws Exception {
    contentType = "text/event-stream";
    replies.add(sse(done()));
    var run =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "fixture-run",
            "fixture-conversation",
            protocolDirectory,
            "fixture-project",
            dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.WEB,
            mode);
    var models = nativeProvider(nativeProperties());
    var nativeModel = models.chatModel(LlmFeature.CHAT);
    var holder = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.service.ModelHolderService.class);
    org.mockito.Mockito.when(holder.get()).thenReturn("fixture-model");
    var client =
        org.springframework.ai.chat.client.ChatClient.builder(
                new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(nativeModel))
            .build();
    var clients =
        new FixedLlmChatClientProvider(client) {
          @Override
          public org.springframework.ai.chat.client.ChatClient parallelChatClient(
              dev.mikoto2000.rei.core.chat.AgentRunContext.Mode requested) {
            return client;
          }
        };
    var service =
        new dev.mikoto2000.rei.core.chat.ChatExecutionService(
            clients,
            holder,
            models,
            new LlmProperties(),
            new dev.mikoto2000.rei.core.service.CommandCancellationService(),
            java.util.Optional.empty(),
            java.util.Optional.empty());
    var logs =
        new dev.mikoto2000.rei.conversation.ConversationLogStore(
            protocolDirectory.resolve("logs"),
            java.time.Clock.systemUTC(),
            new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
    var turns = dev.mikoto2000.rei.conversation.ConversationTurnStore.inMemory();
    org.springframework.test.util.ReflectionTestUtils.invokeMethod(
        service, "setConversationLogStore", logs);
    org.springframework.test.util.ReflectionTestUtils.invokeMethod(
        service, "setConversationTurnStore", turns);
    var result =
        service.execute(
            run, "fixture request", new dev.mikoto2000.rei.core.chat.UserInterventionQueue());
    assertThat(result.success()).as("native main Run: %s", result.stopCode()).isTrue();
    assertThat(result.text()).isEqualTo("done");
    assertThat(requests).hasSize(1);
    assertThat(requests.getFirst().has("max_output_tokens")).isTrue();
    for (String absent : List.of("max_tokens", "max_completion_tokens", "tools", "tool_choice"))
      assertThat(requests.getFirst().has(absent)).as(absent).isFalse();
    var saved =
        protocolStore.usage(
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope.forOwner(run),
            run.runId());
    assertThat(saved.payloadCount()).as("original input and final assistant").isEqualTo(2);
    assertThat(saved.runCumulativeBytes()).isPositive();
    assertThat(saved.referenceCount())
        .as("terminal Run leases are released after history publication")
        .isEqualTo(retainedAfterCleanupFailure ? 4 : 2);
    var protocolRows =
        logs.readConversation(run.conversationId()).stream()
            .filter(entry ->
                dev.mikoto2000.rei.conversation.ConversationLogStore.PROTOCOL_SPEAKER.equals(
                    entry.speaker()))
            .toList();
    assertThat(protocolRows).as("real main path publishes every protocol pointer").hasSize(2);
    assertThat(protocolRows)
        .extracting(entry -> entry.metadata().get("rei.modelMessage.role"))
        .containsExactly("user", "assistant");
    assertThat(turns.read(run.conversationId()).getFirst().metadata())
        .containsAllEntriesOf(
            Map.of(
                dev.mikoto2000.rei.conversation.ConversationLogStore.PROTOCOL_VERSION,
                "1",
                dev.mikoto2000.rei.conversation.ConversationLogStore.PROTOCOL_COUNT,
                "2",
                "rei.modelMessage.run",
                run.runId(),
                "rei.modelMessage.endpoint",
                models.resolvedEndpoint(LlmFeature.CHAT).fingerprint()));
    assertThat(logs.readAll())
        .extracting(dev.mikoto2000.rei.conversation.ConversationLogEntry::content)
        .containsExactly("fixture request", "done");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "ENCRYPTED,endpoint",
    "ENCRYPTED,owner",
    "ENCRYPTED,binding-only",
    "NONE,endpoint",
    "NONE,owner",
    "NONE,binding-only"
  })
  void backendOriginIsTheAuthoritativeRequestBinding(ReasoningReplayMode replay, String mode) {
    replayMode = replay;
    var defaults = options();
    var context = new java.util.HashMap<>(defaults.getToolContext());
    if (mode.equals("binding-only")) context.remove(ResponsesReasoningReplay.OWNER_CONTEXT_KEY);
    context.put(
        dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY,
        new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Origin(
            mode.equals("owner") ? "foreign-owner" : owner,
            mode.equals("endpoint") ? "0".repeat(64) : endpoint(defaults).fingerprint()));
    var request = defaults.mutate().toolContext(null).toolContext(context).build();
    if (mode.equals("binding-only")) {
      var response = model(defaults).call(new Prompt("fixture", request));
      assertThat(response.getResult().getOutput().getMetadata())
          .containsEntry(ResponsesReasoningReplay.ORIGIN_OWNER, owner);
      assertThat(requests).hasSize(1);
    } else {
      assertThatThrownBy(() -> model(defaults).call(new Prompt("fixture", request)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(mode);
      assertThat(requests).isEmpty();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "ENCRYPTED,owner",
    "ENCRYPTED,endpoint",
    "NONE,owner",
    "NONE,endpoint"
  })
  void textOnlyNativeHistoryStillRequiresMatchingOrigin(
      ReasoningReplayMode replay, String mismatch) {
    replayMode = replay;
    var defaults = options();
    var history =
        AssistantMessage.builder()
            .content("previous answer")
            .properties(
                Map.of(
                    ResponsesReasoningReplay.ORIGIN_OWNER,
                        mismatch.equals("owner") ? "foreign-owner" : owner,
                    ResponsesReasoningReplay.ORIGIN_ENDPOINT,
                        mismatch.equals("endpoint")
                            ? "0".repeat(64)
                            : endpoint(defaults).fingerprint()))
            .build();
    assertThatThrownBy(() ->
            model(defaults)
                .call(new Prompt(List.of(history, new UserMessage("continue")), defaults)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(mismatch);
    assertThat(requests).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void existingLoopsPersistDistinctIterationsAndTheFinalAssistant(boolean bounded)
      throws Exception {
    contentType = "text/event-stream";
    replies.add(sse(toolResponse()));
    replies.add(
        sse(
            toolResponse()
                .replace("resp_tools", "resp_tools_second")
                .replace("_one", "_three")
                .replace("_two", "_four")
                .replace("opaque-one", "opaque-three")
                .replace("opaque-two", "opaque-four")
                .replace("msg_work", "msg_work_second")));
    replies.add(sse(done()));
    var accepted =
        new java.util.ArrayList<
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Accepted>();
    var seed = options();
    var originalBoundary =
        (dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange)
            seed.getToolContext()
                .get(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY);
    var boundary =
        new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange(
            originalBoundary.store(),
            new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope(
                "project:fixture-project", protocolDirectory.toString(), "fixture-conversation"),
            originalBoundary.run(),
            originalBoundary.key());
    var options =
        seed.mutate()
            .toolContext(
                Map.of(
                    dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY,
                    boundary.withObserver(accepted::add)))
            .build();
    var run =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "fixture-run", "fixture-conversation", protocolDirectory, "fixture-project");
    var client = model(options);
    if (bounded) {
      var outcome =
          new dev.mikoto2000.rei.core.chat.BoundedToolLoop()
              .runWithHistory(
                  client, new Prompt("fixture", options), new AtomicInteger(3), run, () -> {}, null)
              .block(Duration.ofSeconds(10));
      assertThat(outcome.output()).isEqualTo("done");
    } else {
      var budget = new OutputLimitRunBudget(2, 4);
      budget.tryConsumeLlmCall();
      var execution =
          new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(
              "fixture-run",
              budget,
              new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(protocolDirectory),
              new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
              ignored -> {});
      execution.setRunContext(run);
      execution.setModelOptions(controlledOptions(execution, options));
      var checkpoints =
          new java.util.ArrayList<
              dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState>();
      execution.setToolResultsCheckpoint(ignored -> checkpoints.add(execution.progressSnapshot()));
      var requestOptions =
          execution
              .modelOptions()
              .mutate()
              .toolContext(
                  Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY, execution))
              .build();
      new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(client)
          .stream(new Prompt("fixture", requestOptions)).blockLast(Duration.ofSeconds(10));
      var history = execution.progressSnapshot().protocolHistory();
      assertThat(history).as("ordered durable history in existing progress checkpoint").isNotNull();
      assertThat(history.schemaVersion()).isEqualTo(1);
      assertThat(history.run()).isEqualTo(run.runId());
      assertThat(history.entries())
          .extracting(
              dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.History.Entry::reference)
          .containsExactlyElementsOf(
              accepted.stream()
                  .map(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Accepted::reference)
                  .toList());
      assertThat(history.entries()).hasSize(5);
      assertThat(checkpoints)
          .anySatisfy(snapshot -> assertThat(snapshot.protocolHistory()).isEqualTo(history));
      assertThat(execution.progressSnapshot().forExplicitResume().protocolHistory())
          .isEqualTo(history);
      String document = new ObjectMapper().writeValueAsString(execution.progressSnapshot());
      assertThat(document).doesNotContain("opaque-one", "opaque-two", "fixture result");
      var decoded =
          new ObjectMapper()
              .readValue(
                  document,
                  dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState.class);
      var restored =
          new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(
              "fixture-run",
              new OutputLimitRunBudget(2, 4),
              new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(protocolDirectory),
              new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
              ignored -> {});
      restored.setRunContext(run);
      restored.setModelOptions(controlledOptions(restored, options));
      restored.restoreProgress(decoded);
      assertThat(restored.progressSnapshot().protocolHistory()).isEqualTo(history);
    }
    assertThat(executions).hasValue(4);
    assertThat(requests).hasSize(3);
    assertThat(requests.getLast().path("input").toString())
        .contains("opaque-one+/=", "opaque-two+/=", "opaque-three+/=", "opaque-four+/=");
    var usage = protocolStore.usage(boundary.scope(), boundary.run());
    assertThat(usage.payloadCount())
        .as("five outputs plus input and distinct feedback snapshots")
        .isEqualTo(bounded ? 5 : 8);
    assertThat(accepted).hasSize(5);
    assertThat(accepted)
        .extracting(item -> item.message().getMessageType().name())
        .containsExactly("ASSISTANT", "TOOL", "ASSISTANT", "TOOL", "ASSISTANT");
    assertThat(accepted)
        .extracting(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Accepted::reference)
        .doesNotHaveDuplicates();
    for (var item : accepted) {
      assertThat(
              protocolStore
                  .load(
                      boundary.scope(),
                      item.reference(),
                      dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.RUN,
                      boundary.run())
                  .getMessageType())
          .isEqualTo(item.message().getMessageType());
      assertThat(item.toString()).doesNotContain("opaque-one", "opaque-two", "fixture result");
    }
  }

  @Test
  void checkpointWriteFailureStopsBeforeTheFirstNativeToolEffect() throws Exception {
    contentType = "text/event-stream";
    replies.add(sse(toolResponse()));
    var provider = nativeProvider(nativeProperties());
    var run =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "fixture-run", "fixture-conversation", protocolDirectory, "fixture-project");
    var callbacks = options().getToolCallbacks();
    var request =
        provider
            .requestOptions(LlmFeature.CHAT, null, true, run)
            .mutate()
            .toolCallbacks(callbacks)
            .build();
    var budget = new OutputLimitRunBudget(2, 4);
    budget.tryConsumeLlmCall();
    var execution =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(
            run.runId(),
            budget,
            new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(protocolDirectory),
            new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
            ignored -> {});
    execution.setRunContext(run);
    execution.setModelOptions(request);
    var attempted =
        new java.util.concurrent.atomic.AtomicReference<
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.History>();
    execution.setToolResultsCheckpoint(ignored -> {
      var proposed = execution.progressSnapshot().protocolHistory();
      if (!proposed.entries().isEmpty()) {
        attempted.set(proposed);
        throw new IllegalStateException("checkpoint unavailable");
      }
    });
    var accepted =
        execution
            .modelOptions()
            .mutate()
            .toolContext(
                Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY, execution))
            .build();
    assertThatThrownBy(() ->
            new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(
                    provider.chatModel(LlmFeature.CHAT))
                .stream(new Prompt("fixture", accepted)).blockLast(Duration.ofSeconds(10)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("checkpoint unavailable")
        .hasMessageNotContaining("opaque-one");
    assertThat(executions).hasValue(0);
    assertThat(requests).hasSize(1);
    assertThat(attempted.get().entries()).hasSize(1);
    assertThat(execution.progressSnapshot().protocolHistory().entries()).isEmpty();
    assertThat(execution.progressSnapshot().protocolHistory().input()).isNotNull();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "missing,false",
    "missing,true",
    "zero,false",
    "zero,true"
  })
  void missingNativeUsageRemainsUnknownThroughAggregationAndPersistence(
      String kind, boolean streaming) throws Exception {
    var mapper = new ObjectMapper();
    var raw = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(reasoningDone());
    var expected =
        new dev.mikoto2000.rei.modelmessage.ModelTokenUsage(null, null, null, null, null);
    if (kind.equals("missing")) raw.remove("usage");
    else {
      var usage =
          raw.putObject("usage")
              .put("input_tokens", 0)
              .put("output_tokens", 0)
              .put("total_tokens", 0);
      usage.putObject("input_tokens_details").put("cached_tokens", 0);
      usage.putObject("output_tokens_details").put("reasoning_tokens", 0);
      expected = new dev.mikoto2000.rei.modelmessage.ModelTokenUsage(0L, 0L, 0L, 0L, 0L);
    }
    contentType = streaming ? "text/event-stream" : "application/json";
    replies.add(streaming ? sse(raw.toString()) : raw.toString());
    var options = options();
    var model = model(options);
    var prompt = new Prompt("fixture", options);
    org.springframework.ai.chat.model.ChatResponse response;
    if (streaming) {
      var completed =
          new java.util.concurrent.atomic.AtomicReference<
              org.springframework.ai.chat.model.ChatResponse>();
      new org.springframework.ai.chat.model.MessageAggregator()
          .aggregate(model.stream(prompt), completed::set)
          .blockLast(Duration.ofSeconds(10));
      response = completed.get();
    } else response = model.call(prompt);
    assertThat(ResponsesTransportException.usage(response)).isEqualTo(expected);
    var codec = new dev.mikoto2000.rei.modelmessage.ModelMessageCodec();
    var restored = codec.decode(codec.encode(response.getResult().getOutput()));
    assertThat(restored.getMetadata())
        .containsEntry(dev.mikoto2000.rei.modelmessage.ModelTokenUsage.METADATA_KEY, expected);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "negative,false",
    "negative,true",
    "sum,false",
    "cached,true",
    "reasoning,false",
    "fraction,false",
    "fraction,true",
    "overflow,false",
    "typed,false",
    "cachedFraction,true",
    "detailType,true",
    "cachedOverflow,false"
  })
  void invalidNativeUsageCannotBeRoundedStoredOrDispatched(String changed, boolean streaming)
      throws Exception {
    String raw =
        switch (changed) {
          case "negative" -> toolResponse().replace("\"input_tokens\":3", "\"input_tokens\":-1");
          case "sum" -> toolResponse().replace("\"total_tokens\":5", "\"total_tokens\":4");
          case "cached" -> toolResponse().replace("\"cached_tokens\":1", "\"cached_tokens\":4");
          case "reasoning" ->
              toolResponse().replace("\"reasoning_tokens\":1", "\"reasoning_tokens\":3");
          case "fraction" -> toolResponse().replace("\"total_tokens\":5", "\"total_tokens\":5.5");
          case "overflow" ->
              toolResponse().replace("\"input_tokens\":3", "\"input_tokens\":18446744073709551619");
          case "typed" -> toolResponse().replace("\"input_tokens\":3", "\"input_tokens\":\"3\"");
          case "cachedFraction" ->
              toolResponse().replace("\"cached_tokens\":1", "\"cached_tokens\":1.5");
          case "detailType" ->
              toolResponse()
                  .replace(
                      "\"input_tokens_details\":{\"cached_tokens\":1}",
                      "\"input_tokens_details\":\"private-fixture\"");
          case "cachedOverflow" ->
              toolResponse()
                  .replace("\"cached_tokens\":1", "\"cached_tokens\":18446744073709551617");
          default -> throw new AssertionError(changed);
        };
    contentType = streaming ? "text/event-stream" : "application/json";
    replies.add(streaming ? sse(raw) : raw);
    var options = options();
    var model = model(options);
    var failure =
        org.assertj.core.api.Assertions.catchThrowable(() -> {
          if (streaming) {
            var run =
                new dev.mikoto2000.rei.core.chat.AgentRunContext(
                    "usage-run",
                    "session",
                    java.nio.file.Path.of("."),
                    "project",
                    dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.SHELL,
                    dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE);
            new dev.mikoto2000.rei.core.chat.BoundedToolLoop()
                .run(model, new Prompt("fixture", options), 2, run, () -> {})
                .block(Duration.ofSeconds(10));
          } else
            new ToolLoopSupport()
                .execute(
                    new Prompt("fixture", options), model.call(new Prompt("fixture", options)));
        });
    assertThat(executions.get()).isZero();
    assertThat(failure)
        .isInstanceOf(ResponsesTransportException.class)
        .hasMessageContaining("INVALID_RESPONSE")
        .hasNoCause();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void nativeAssistantSurvivesSqliteRestartAndReplaysWithoutLosingUsageOrPayload(
      boolean streaming, @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
      throws Exception {
    contentType = streaming ? "text/event-stream" : "application/json";
    replies.add(streaming ? sse(reasoningDone()) : reasoningDone());
    var options = options();
    var model = model(options);
    org.springframework.ai.chat.model.ChatResponse response;
    if (streaming) {
      var completed =
          new java.util.concurrent.atomic.AtomicReference<
              org.springframework.ai.chat.model.ChatResponse>();
      new org.springframework.ai.chat.model.MessageAggregator()
          .aggregate(model.stream(new Prompt("fixture", options)), completed::set)
          .blockLast(Duration.ofSeconds(10));
      response = completed.get();
    } else response = model.call(new Prompt("fixture", options));
    var original = response.getResult().getOutput();
    var codec = new dev.mikoto2000.rei.modelmessage.ModelMessageCodec();
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("messages.db"));
    var store = new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore(source);
    var scope =
        new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope(
            "fixture-project", directory.toString(), "fixture-conversation");
    var ref = store.save(scope, "fixture-run", "assistant", original);
    var pointer = store.historyReference(scope, ref, "fixture-history");
    var restored =
        (AssistantMessage)
            new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore(source)
                .restoreHistory(scope, pointer)
                .orElseThrow();
    assertThat(codec.encode(restored)).containsExactly(codec.encode(original));
    assertThat(restored.getParts()).containsExactlyElementsOf(original.getParts());
    var restoredResponse =
        new org.springframework.ai.chat.model.ChatResponse(
            List.of(new org.springframework.ai.chat.model.Generation(restored)));
    assertThat(ResponsesTransportException.usage(restoredResponse))
        .isEqualTo(new dev.mikoto2000.rei.modelmessage.ModelTokenUsage(3L, 2L, 5L, 1L, 1L));
    contentType = "application/json";
    model.call(new Prompt(List.of(new UserMessage("fixture"), restored), options));
    assertThat(requests).hasSize(2);
    assertThat(requests.getLast().path("input").get(1).path("encrypted_content").asText())
        .isEqualTo("opaque-bound");
  }

  @Test
  void outputLimitReachesTheExistingRunHandoffAndTokenBudgetWithoutDispatch() throws Exception {
    contentType = "text/event-stream";
    var truncated =
        toolResponse()
            .replaceFirst(
                "\"status\":\"completed\"",
                "\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}");
    replies.add(sse(truncated).replace("response.completed", "response.incomplete"));
    var budget = new OutputLimitRunBudget(3, 10, null, 1000);
    budget.tryConsumeLlmCall();
    var context =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(
            "limit-run",
            budget,
            new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(java.nio.file.Path.of(".")),
            new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
            event -> {});
    context.setRunContext(
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "limit-run",
            "session",
            java.nio.file.Path.of("."),
            "project",
            dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.SHELL,
            dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE));
    var defaults = controlledOptions(context, options());
    var options =
        defaults
            .mutate()
            .toolContext(
                Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY, context))
            .build();
    new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(model(defaults))
        .stream(new Prompt("fixture", options)).blockLast(Duration.ofSeconds(10));
    assertThat(executions.get()).isZero();
    assertThat(context.outputLimitHandoff())
        .isNotBlank()
        .doesNotContain("opaque-one", "opaque-two");
    assertThat(budget.totalTokens()).isEqualTo(5);
    assertThat(budget.snapshot().usageUnknown()).isFalse();
    assertThat(requests).hasSize(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = dev.mikoto2000.rei.core.policy.PermissionDecision.class,
      names = {"DENY", "REQUIRE_APPROVAL"})
  void nativeToolCallsStillPassThroughExistingPermissionAndApprovalGuards(
      dev.mikoto2000.rei.core.policy.PermissionDecision decision) throws Exception {
    contentType = "text/event-stream";
    replies.add(sse(toolResponse()));
    var budget = new OutputLimitRunBudget(3, 10);
    budget.tryConsumeLlmCall();
    var context =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(
            "guard-run",
            budget,
            new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(java.nio.file.Path.of(".")),
            new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
            event -> {});
    context.setRunContext(
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "guard-run",
            "session",
            java.nio.file.Path.of("."),
            "project",
            dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.SHELL,
            dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE));
    var guard = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.policy.ToolPermissionGuard.class);
    org.mockito.Mockito.doThrow(
            new dev.mikoto2000.rei.core.policy.ToolPermissionException("fixture_tool", decision))
        .when(guard)
        .check(
            org.mockito.ArgumentMatchers.eq("fixture_tool"),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    context.setToolPermissionGuard(guard);
    var defaults = controlledOptions(context, options());
    var guarded =
        defaults
            .mutate()
            .toolContext(
                Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY, context))
            .build();
    var failure =
        org.assertj.core.api.Assertions.catchThrowable(() ->
            new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(model(defaults))
                .stream(new Prompt("fixture", guarded)).blockLast(Duration.ofSeconds(10)));
    assertThat(failure).isNotNull();
    assertThat(executions.get()).isZero();
    org.mockito.Mockito.verify(guard)
        .check(
            org.mockito.ArgumentMatchers.eq("fixture_tool"),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    assertThat(requests).hasSize(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"provider", "kind"})
  void foreignPayloadKindsAreNotSilentlyDroppedOrTreatedAsEncryptedReasoning(String changed) {
    var payload =
        new OpaquePayload(
            changed.equals("provider") ? "foreign-provider" : OpenAiResponsesMetadata.PROVIDER,
            changed.equals("kind")
                ? "foreign-signature"
                : OpenAiResponsesMetadata.ENCRYPTED_CONTENT_KIND,
            "private-fixture-payload");
    var assistant =
        AssistantMessage.builder()
            .part(
                new ReasoningPart(
                    null,
                    null,
                    payload,
                    Map.of(OpenAiResponsesMetadata.ITEM_ID_ATTRIBUTE, "rs_fixture")))
            .build();
    var options = options();
    var prompt = new Prompt(List.of(new UserMessage("fixture"), assistant), options);
    assertThatThrownBy(() -> model(options).call(prompt))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("payload")
        .hasMessageNotContaining("private-fixture-payload")
        .hasNoCause();
    assertThat(requests).isEmpty();
  }

  private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
  private final ArrayDeque<String> replies = new ArrayDeque<>();
  private final ArrayDeque<Integer> httpStatuses = new ArrayDeque<>();
  private final AtomicInteger executions = new AtomicInteger();
  private String owner = "fixture-owner";
  private String endpointId = "fixture";
  private long generation = 1;
  private String modelName = "fixture-model";
  private String baseUrl = "https://responses-fixture.invalid/v1";
  private ReasoningReplayMode replayMode = ReasoningReplayMode.ENCRYPTED;
  private String contentType = "application/json";
  private String retryHint;
  private final AtomicInteger timeoutFailures = new AtomicInteger();

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"eof", "failed", "incomplete", "cancelled", "outputLimit"})
  void existingBoundedLoopNeverDispatchesBeforeAValidTerminalEvent(String ending) throws Exception {
    String raw = toolResponse();
    if (!ending.equals("eof"))
      raw =
          raw.replaceFirst(
              "\"status\":\"completed\"",
              "\"status\":\""
                  + (ending.equals("outputLimit") ? "incomplete" : ending)
                  + "\""
                  + (ending.equals("outputLimit")
                      ? ",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}"
                      : ""));
    String stream = sse(raw);
    if (ending.equals("eof"))
      stream = stream.substring(0, stream.indexOf("event: response.completed"));
    else
      stream =
          stream.replace(
              "response.completed",
              "response." + (ending.equals("outputLimit") ? "incomplete" : ending));
    contentType = "text/event-stream";
    replies.add(stream);
    var options = options();
    var run =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "run",
            "session",
            java.nio.file.Path.of("."),
            "project",
            dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.SHELL,
            dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE);
    var failure =
        org.assertj.core.api.Assertions.catchThrowable(() ->
            new dev.mikoto2000.rei.core.chat.BoundedToolLoop()
                .run(model(options), new Prompt("fixture", options), 2, run, () -> {})
                .block(Duration.ofSeconds(10)));
    assertThat(executions.get()).isZero();
    assertThat(failure).isNotNull();
    assertThat(requests).hasSize(1);
  }

  @Test
  void cancellingTheExistingLoopWhileToolItemsAreBufferedNeverDispatches() throws Exception {
    contentType = "text/event-stream";
    replies.add(sse(toolResponse()));
    var options = options();
    var nativeModel = model(options);
    var toolReceived = new java.util.concurrent.CountDownLatch(1);
    org.springframework.ai.chat.model.ChatModel paused =
        new org.springframework.ai.chat.model.ChatModel() {
          public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
            return nativeModel.call(prompt);
          }

          public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(
              Prompt prompt) {
            return nativeModel.stream(prompt)
                .concatMap(
                    chunk -> {
                      if (chunk.hasToolCalls()) {
                        toolReceived.countDown();
                        return reactor.core.publisher.Mono
                            .<org.springframework.ai.chat.model.ChatResponse>never();
                      }
                      return reactor.core.publisher.Mono.just(chunk);
                    },
                    1);
          }
        };
    var run =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "run",
            "session",
            java.nio.file.Path.of("."),
            "project",
            dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.SHELL,
            dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE);
    var subscription =
        new dev.mikoto2000.rei.core.chat.BoundedToolLoop()
            .run(paused, new Prompt("fixture", options), 2, run, () -> {})
            .subscribe();
    try {
      assertThat(toolReceived.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    } finally {
      subscription.dispose();
    }
    assertThat(executions.get()).isZero();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void partialOrHostedSavedPartsCannotBeSilentlyDroppedOrReplayed(boolean partial) {
    org.springframework.ai.chat.messages.part.MessagePart part =
        partial
            ? org.springframework.ai.chat.messages.part.StreamingParts.partial(
                org.springframework.ai.chat.messages.part.ToolCallPart.of(
                    new AssistantMessage.ToolCall("call_partial", "function", "fixture_tool", "{")),
                0)
            : new org.springframework.ai.chat.messages.part.UnknownPart(
                "openai",
                "web_search_call",
                "{\"type\":\"web_search_call\",\"id\":\"ws_one\",\"status\":\"completed\"}",
                null,
                Map.of());
    var assistant = AssistantMessage.builder().part(part).build();
    var options = options();
    assertThatThrownBy(() ->
            model(options)
                .call(new Prompt(List.of(new UserMessage("fixture"), assistant), options)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("part");
    assertThat(requests).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void existingDiscoveryLoopUsesTheOriginalRequestSnapshot(boolean unpublishedSibling)
      throws Exception {
    contentType = "text/event-stream";
    var mapper = new ObjectMapper();
    var output = mapper.createArrayNode();
    output.add(
        mapper.readTree(
            """
            {"type":"reasoning","id":"rs_search","summary":[],"encrypted_content":"opaque-search","status":"completed"}
            """));
    var search =
        mapper
            .createObjectNode()
            .put("type", "function_call")
            .put("id", "fc_search")
            .put("call_id", "call_search")
            .put("name", "searchTools")
            .put("status", "completed")
            .put("arguments", "{\"query\":\"googleCalendarListEventsForDate\",\"maxResults\":1}");
    output.add(search);
    if (unpublishedSibling)
      output.add(
          search
              .deepCopy()
              .put("id", "fc_unpublished")
              .put("call_id", "call_unpublished")
              .put("name", "googleCalendarListEventsForDate")
              .put("arguments", "{}"));
    replies.add(sse(response("resp_search", output.toString())));
    replies.add(sse(done()));
    var budget = new OutputLimitRunBudget(3, 10);
    budget.tryConsumeLlmCall();
    var execution =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(
            "run",
            budget,
            new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(java.nio.file.Path.of(".")),
            new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
            event -> {});
    execution.setRunContext(
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "run",
            "session",
            java.nio.file.Path.of("."),
            "project",
            dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.SHELL,
            dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE));
    var callbacks =
        java.nio.file.Files.readAllLines(
                java.nio.file.Path.of("docs/tool-discovery-classification.csv"))
            .stream()
            .skip(1)
            .filter(line -> !line.isBlank())
            .<ToolCallback>map(line ->
                new ToolCallback() {
                  public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder()
                        .name(line.split(",")[0])
                        .description("fixture")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build();
                  }

                  public String call(String arguments) {
                    executions.incrementAndGet();
                    return "fixture";
                  }
                })
            .toList();
    var defaults = controlledOptions(execution, options());
    var requestOptions =
        defaults
            .mutate()
            .toolCallbacks(callbacks)
            .toolContext(
                Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY, execution))
            .build();
    var discoveryProperties = new dev.mikoto2000.rei.llm.discovery.ToolDiscoveryProperties();
    discoveryProperties.setEnabled(true);
    var discovery =
        new dev.mikoto2000.rei.llm.discovery.ToolDiscovery(
            discoveryProperties,
            new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                .getBeanProvider(dev.mikoto2000.rei.llm.discovery.ToolDiscoveryStore.class));
    new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(model(defaults))
        .withToolDiscovery(discovery).stream(new Prompt("fixture", requestOptions))
            .blockLast(Duration.ofSeconds(10));
    assertThat(executions.get()).isZero();
    assertThat(requests).hasSize(2);
    assertThat(requests.getFirst().path("tools")).hasSize(32);
    assertThat(requests.getFirst().path("tools").toString())
        .doesNotContain("googleCalendarListEventsForDate");
    assertThat(requests.getLast().path("tools")).hasSize(unpublishedSibling ? 32 : 33);
    if (!unpublishedSibling) {
      assertThat(requests.getLast().path("tools").toString())
          .contains("googleCalendarListEventsForDate");
      assertThat(requests.getLast().path("input").toString())
          .contains("opaque-search", "call_search");
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "status,false",
    "status,true",
    "duplicateItem,false",
    "duplicateItem,true",
    "duplicateCall,false",
    "duplicateCall,true",
    "arguments,false",
    "arguments,true",
    "hosted,false",
    "hosted,true"
  })
  void malformedCompletedOutputCannotDispatch(String changed, boolean streaming) throws Exception {
    String raw =
        switch (changed) {
          case "status" ->
              toolResponse()
                  .replace(
                      "\"id\":\"fc_one\",\"call_id\":\"call_one\",\"name\":\"fixture_tool\",\"arguments\":\"{}\",\"status\":\"completed\"",
                      "\"id\":\"fc_one\",\"call_id\":\"call_one\",\"name\":\"fixture_tool\",\"arguments\":\"{}\",\"status\":\"in_progress\"");
          case "duplicateItem" -> toolResponse().replace("fc_two", "fc_one");
          case "duplicateCall" -> toolResponse().replace("call_two", "call_one");
          case "arguments" -> toolResponse().replace("\"arguments\":\"{}\"", "\"arguments\":\"{\"");
          case "hosted" ->
              response(
                  "resp_hosted",
                  "[{\"type\":\"web_search_call\",\"id\":\"ws_one\",\"status\":\"completed\",\"action\":{\"type\":\"search\",\"query\":\"fixture\"}}]");
          default -> throw new AssertionError(changed);
        };
    contentType = streaming ? "text/event-stream" : "application/json";
    replies.add(streaming ? sse(raw) : raw);
    var options = options();
    var model = model(options);
    var failure =
        org.assertj.core.api.Assertions.catchThrowable(() -> {
          if (streaming)
            model.stream(new Prompt("fixture", options)).blockLast(Duration.ofSeconds(10));
          else
            new ToolLoopSupport()
                .execute(
                    new Prompt("fixture", options), model.call(new Prompt("fixture", options)));
        });
    assertThat(executions.get()).isZero();
    assertThat(failure)
        .isInstanceOf(ResponsesTransportException.class)
        .hasMessageContaining("INVALID_RESPONSE");
    assertThat(((ResponsesTransportException) failure).usage())
        .isEqualTo(new dev.mikoto2000.rei.modelmessage.ModelTokenUsage(3L, 2L, 5L, 1L, 1L));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void completedStatusWithoutRequiredReasoningDataCannotReachBusinessDispatch(boolean streaming)
      throws Exception {
    var invalid = toolResponse().replace("\"encrypted_content\":\"opaque-one+/=\",", "");
    contentType = streaming ? "text/event-stream" : "application/json";
    replies.add(streaming ? sse(invalid) : invalid);
    var options = options();
    var model = model(options);
    var prompt = new Prompt("fixture", options);
    var failure =
        org.assertj.core.api.Assertions.catchThrowable(() -> {
          if (streaming) {
            var run =
                new dev.mikoto2000.rei.core.chat.AgentRunContext(
                    "run",
                    "session",
                    java.nio.file.Path.of("."),
                    "project",
                    dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.SHELL,
                    dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE);
            new dev.mikoto2000.rei.core.chat.BoundedToolLoop()
                .run(model, prompt, 2, run, () -> {})
                .block(Duration.ofSeconds(10));
          } else {
            new ToolLoopSupport().execute(prompt, model.call(prompt));
          }
        });
    assertThat(executions.get()).isZero();
    assertThat(failure)
        .isInstanceOf(ResponsesTransportException.class)
        .hasMessageContaining("INVALID_RESPONSE");
    assertThat(requests).hasSize(1);
  }

  @Test
  void streamingPartsRetainTheirOwnerAndCanContinueThroughTheExistingToolBoundary()
      throws Exception {
    contentType = "text/event-stream";
    replies.add(sse(toolResponse()));
    var options = options();
    var model = model(options);
    var prompt = new Prompt("fixture", options);
    var completed =
        new java.util.concurrent.atomic.AtomicReference<
            org.springframework.ai.chat.model.ChatResponse>();
    new org.springframework.ai.chat.model.MessageAggregator()
        .aggregate(model.stream(prompt), completed::set)
        .blockLast(Duration.ofSeconds(10));
    var first = completed.get();
    assertThat(ResponsesTransportException.usage(first))
        .isEqualTo(new dev.mikoto2000.rei.modelmessage.ModelTokenUsage(3L, 2L, 5L, 1L, 1L));
    assertThat(first.getResult().getOutput().getMetadata().get("rei.llm.replay-origin-owner"))
        .isEqualTo("fixture-owner");
    assertThat(first.getResult().getOutput().getParts()).hasSize(5);
    assertThat(executions.get()).isZero();
    var executed = new ToolLoopSupport().execute(prompt, first);
    assertThat(executions.get()).isEqualTo(2);
    contentType = "application/json";
    model.call(new Prompt(executed.conversationHistory(), options));
    assertThat(requests).hasSize(2);
    assertThat(requests.getLast().path("input").get(1).path("encrypted_content").asText())
        .isEqualTo("opaque-one+/=");
    assertThat(requests.getLast().path("input").get(4).path("encrypted_content").asText())
        .isEqualTo("opaque-two+/=");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void plaintextReasoningUsesThePublicSdkSendViewWithoutMutatingTheStoredOriginal(boolean streaming)
      throws Exception {
    replayMode = ReasoningReplayMode.PLAINTEXT;
    var wire = new ObjectMapper().readTree(toolResponse());
    for (var item : wire.path("output"))
      if ("reasoning".equals(item.path("type").asText())) {
        var reasoning = (com.fasterxml.jackson.databind.node.ObjectNode) item;
        reasoning.remove("encrypted_content");
        reasoning.set(
            "content",
            new ObjectMapper()
                .readTree(
                    "[{\"type\":\"reasoning_text\",\"text\":\"PRIVATE-平文-"
                        + item.path("id").asText()
                        + "\"}]"));
      }
    contentType = streaming ? "text/event-stream" : "application/json";
    replies.add(streaming ? sse(wire.toString()) : wire.toString());
    var options = options();
    var model = model(options);
    var prompt = new Prompt("fixture", options);
    org.springframework.ai.chat.model.ChatResponse first;
    if (streaming) {
      var completed =
          new java.util.concurrent.atomic.AtomicReference<
              org.springframework.ai.chat.model.ChatResponse>();
      new org.springframework.ai.chat.model.MessageAggregator()
          .aggregate(model.stream(prompt), completed::set)
          .blockLast(Duration.ofSeconds(10));
      first = completed.get();
    } else first = model.call(prompt);
    var original = first.getResult().getOutput();
    var parts = List.copyOf(original.getParts());
    assertThat(original.getText()).isEqualTo("working");
    var reasoning =
        original.getParts().stream()
            .filter(ReasoningPart.class::isInstance)
            .map(ReasoningPart.class::cast)
            .toList();
    assertThat(reasoning).hasSize(2);
    assertThat(reasoning)
        .allSatisfy(part -> {
          assertThat(part.payload()).isNull();
          assertThat(part.text()).startsWith("PRIVATE-平文-");
        });
    var scope =
        new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope(
            "fixture-project", protocolDirectory.toString(), "fixture-conversation");
    var ref = payloads().save(scope, "fixture-run", "plaintext-original", original);
    payloads()
        .publish(
            scope,
            ref,
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.RUN,
            "fixture-run");
    var restored =
        (AssistantMessage)
            payloads()
                .load(
                    scope,
                    ref,
                    dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.RUN,
                    "fixture-run");
    assertThat(restored.getParts()).containsExactlyElementsOf(parts);
    contentType = "application/json";
    model.call(new Prompt(List.of(new UserMessage("fixture"), restored), options));
    assertThat(requests).allSatisfy(request -> assertThat(request.has("include")).isFalse());
    var input = requests.getLast().path("input");
    for (int position : List.of(1, 4)) {
      assertThat(input.get(position).path("type").asText()).isEqualTo("reasoning");
      assertThat(input.get(position).path("content").get(0).path("text").asText())
          .isEqualTo("PRIVATE-平文-" + input.get(position).path("id").asText());
      assertThat(input.get(position).has("encrypted_content")).isFalse();
    }
    assertThat(original.getParts()).containsExactlyElementsOf(parts);
    assertThat(executions.get()).isZero();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "parallel",
        "summary",
        "verbosity",
        "temperature",
        "top-p",
        "include",
        "metadata",
        "service-tier",
        "cache-key",
        "safety"
      })
  void plaintextUnsupportedSettingsAreRejectedBeforeTheProviderCanSilentlyIgnoreThem(
      String setting) {
    replayMode = ReasoningReplayMode.PLAINTEXT;
    var builder = options().mutate();
    switch (setting) {
      case "parallel" -> builder.parallelToolCalls(false);
      case "summary" -> builder.reasoningSummary("auto");
      case "verbosity" -> builder.verbosity("low");
      case "temperature" -> builder.temperature(0.2);
      case "top-p" -> builder.topP(0.95);
      case "include" -> builder.include(List.of("reasoning.encrypted_content"));
      case "metadata" -> builder.metadata(Map.of("fixture", "PRIVATE-metadata"));
      case "service-tier" -> builder.serviceTier("auto");
      case "cache-key" -> builder.promptCacheKey("PRIVATE-cache-key");
      case "safety" -> builder.safetyIdentifier("PRIVATE-safety");
      default -> throw new AssertionError(setting);
    }
    var supplied = builder.build();
    assertThatThrownBy(() -> model(options()).call(new Prompt("fixture", supplied)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("PRIVATE");
    assertThat(requests).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "empty",
        "missing",
        "opaque",
        "summary",
        "attribute",
        "owner",
        "endpoint",
        "partial"
      })
  void plaintextReplayRejectsMissingForeignOrLossyPartsBeforeHttp(String defect) {
    replayMode = ReasoningReplayMode.PLAINTEXT;
    replies.add(reasoningDone());
    var options = options();
    var model = model(options);
    var original = model.call(new Prompt("fixture", options)).getResult().getOutput();
    var part = (ReasoningPart) original.getParts().getFirst();
    var attributes = new java.util.HashMap<>(part.attributes());
    var metadata = new java.util.HashMap<>(original.getMetadata());
    if (defect.equals("attribute")) attributes.put("PRIVATE-future-field", "PRIVATE-value");
    if (defect.equals("owner")) metadata.put(ResponsesReasoningReplay.ORIGIN_OWNER, "other-owner");
    if (defect.equals("endpoint"))
      metadata.put(ResponsesReasoningReplay.ORIGIN_ENDPOINT, "other-endpoint");
    org.springframework.ai.chat.messages.part.MessagePart changed =
        new ReasoningPart(
            defect.equals("missing") ? null : defect.equals("empty") ? "" : part.text(),
            defect.equals("summary") ? "PRIVATE-summary" : null,
            defect.equals("opaque")
                ? new OpaquePayload(
                    OpenAiResponsesMetadata.PROVIDER,
                    OpenAiResponsesMetadata.ENCRYPTED_CONTENT_KIND,
                    "PRIVATE-fake-opaque")
                : null,
            attributes);
    if (defect.equals("partial"))
      changed = org.springframework.ai.chat.messages.part.StreamingParts.partial(changed, 0);
    var assistant = AssistantMessage.builder().part(changed).properties(metadata).build();
    assertThatThrownBy(
            () -> model.call(new Prompt(List.of(new UserMessage("fixture"), assistant), options)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("PRIVATE");
    assertThat(requests).hasSize(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "false,item",
    "true,item",
    "false,content",
    "true,content"
  })
  void unknownPlaintextWireFieldsCannotDisappearInTheSpringMapper(boolean streaming, String field)
      throws Exception {
    replayMode = ReasoningReplayMode.PLAINTEXT;
    var wire = new ObjectMapper().readTree(reasoningDone());
    var item = (com.fasterxml.jackson.databind.node.ObjectNode) wire.path("output").get(0);
    var target =
        field.equals("item")
            ? item
            : (com.fasterxml.jackson.databind.node.ObjectNode) item.path("content").get(0);
    target.put("PRIVATE-future", "PRIVATE-value");
    contentType = streaming ? "text/event-stream" : "application/json";
    replies.add(streaming ? sse(wire.toString()) : wire.toString());
    var options = options();
    var model = model(options);
    assertThatThrownBy(
            () -> {
              if (streaming)
                model.stream(new Prompt("fixture", options)).blockLast(Duration.ofSeconds(10));
              else model.call(new Prompt("fixture", options));
            })
        .isInstanceOf(ResponsesTransportException.class)
        .hasMessageContaining("INVALID_RESPONSE")
        .hasMessageNotContaining("PRIVATE")
        .hasNoCause();
    assertThat(executions.get()).isZero();
  }

  @Test
  void encryptedRequestsRequireAnExplicitOwnerBeforeAnyHttp() {
    owner = null;
    var options = options();
    assertThatThrownBy(() -> model(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("owner");
    assertThat(requests).isEmpty();
  }

  @Test
  void anotherOwnerCannotReplayAResponseFromTheSameEndpoint() {
    replies.add(reasoningDone());
    var options = options();
    var assistant = model(options).call(new Prompt("fixture", options)).getResult().getOutput();
    owner = "foreign-owner";
    var changed = options();
    assertThatThrownBy(() ->
            model(changed)
                .call(new Prompt(List.of(new UserMessage("fixture"), assistant), changed)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("owner")
        .hasMessageNotContaining("foreign-owner");
    assertThat(requests).hasSize(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"endpoint", "generation", "model", "url", "mode"})
  void incompatibleEndpointIdentityCannotReceiveEncryptedReplay(String changed) {
    replies.add(reasoningDone());
    var options = options();
    var assistant = model(options).call(new Prompt("fixture", options)).getResult().getOutput();
    switch (changed) {
      case "endpoint" -> endpointId = "other-endpoint";
      case "generation" -> generation = 2;
      case "model" -> modelName = "other-model";
      case "url" -> baseUrl = "https://other.invalid/v1";
      case "mode" -> replayMode = ReasoningReplayMode.NONE;
      default -> throw new AssertionError(changed);
    }
    var target = options();
    assertThatThrownBy(() ->
            model(target).call(new Prompt(List.of(new UserMessage("fixture"), assistant), target)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("replay");
    assertThat(requests).hasSize(1);
  }

  @Test
  void anUnboundReasoningPartCannotClaimCurrentEndpointProvenance() {
    var part =
        new ReasoningPart(
            null,
            null,
            new OpaquePayload(
                OpenAiResponsesMetadata.PROVIDER,
                OpenAiResponsesMetadata.ENCRYPTED_CONTENT_KIND,
                "opaque"),
            Map.of(OpenAiResponsesMetadata.ITEM_ID_ATTRIBUTE, "rs_fixture"));
    var assistant = AssistantMessage.builder().part(part).build();
    var options = options();
    assertThatThrownBy(() ->
            model(options)
                .call(new Prompt(List.of(new UserMessage("fixture"), assistant), options)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("origin");
    assertThat(requests).isEmpty();
  }

  @Test
  void credentialRotationDoesNotInvalidateAnOtherwiseIdenticalReplayBinding() {
    replies.add(reasoningDone());
    var options = options();
    var assistant = model(options).call(new Prompt("fixture", options)).getResult().getOutput();
    var originalParts = List.copyOf(assistant.getParts());
    var rotated = options.mutate().apiKey("rotated-fixture-secret").build();
    model(rotated).call(new Prompt(List.of(new UserMessage("fixture"), assistant), rotated));
    assertThat(requests).hasSize(2);
    assertThat(requests.getLast().path("input").get(1).path("encrypted_content").asText())
        .isEqualTo("opaque-bound");
    assertThat(assistant.getParts()).containsExactlyElementsOf(originalParts);
  }

  @Test
  void existingToolDispatchReplaysEveryEncryptedPartIdAndPhaseWithoutRewriting() {
    replies.add(toolResponse());
    var options = options();
    var model = model(options);
    var prompt = new Prompt("fixture", options);
    var first = model.call(prompt);
    assertThat(executions.get()).isZero();
    assertThat(first.getResult().getOutput().getParts()).hasSize(5);
    var executed = new ToolLoopSupport().execute(prompt, first);
    assertThat(executions.get()).isEqualTo(2);
    var second = model.call(new Prompt(executed.conversationHistory(), options));
    assertThat(second.getResult().getOutput().getText()).isEqualTo("done");
    assertThat(requests).hasSize(2);
    var input = requests.getLast().path("input");
    var types = new java.util.ArrayList<String>();
    input.forEach(item -> types.add(item.path("type").asText("message")));
    assertThat(types)
        .containsExactly(
            "message",
            "reasoning",
            "function_call",
            "message",
            "reasoning",
            "function_call",
            "function_call_output",
            "function_call_output");
    assertThat(input.get(1).path("encrypted_content").asText().getBytes(StandardCharsets.UTF_8))
        .containsExactly("opaque-one+/=".getBytes(StandardCharsets.UTF_8));
    assertThat(input.get(4).path("encrypted_content").asText().getBytes(StandardCharsets.UTF_8))
        .containsExactly("opaque-two+/=".getBytes(StandardCharsets.UTF_8));
    assertThat(input.get(2).path("id").asText()).isEqualTo("fc_one");
    assertThat(input.get(2).path("call_id").asText()).isEqualTo("call_one");
    assertThat(input.get(3).path("phase").asText()).isEqualTo("commentary");
    assertThat(input.get(6).path("call_id").asText()).isEqualTo("call_one");
    assertThat(input.get(6).has("id")).isFalse();
    assertThat(requests.getLast().path("store").asBoolean(true)).isFalse();
    assertThat(requests.getLast().has("previous_response_id")).isFalse();
  }

  private String toolResponse() {
    return response(
        "resp_tools",
        """
        [{"type":"reasoning","id":"rs_one","summary":[],"encrypted_content":"opaque-one+/=","status":"completed"},
         {"type":"function_call","id":"fc_one","call_id":"call_one","name":"fixture_tool","arguments":"{}","status":"completed"},
         {"type":"message","id":"msg_work","role":"assistant","status":"completed","phase":"commentary",
          "content":[{"type":"output_text","text":"working","annotations":[]}]},
         {"type":"reasoning","id":"rs_two","summary":[],"encrypted_content":"opaque-two+/=","status":"completed"},
         {"type":"function_call","id":"fc_two","call_id":"call_two","name":"fixture_tool","arguments":"{}","status":"completed"}]
        """);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"payload", "item id"})
  void missingRequiredEncryptedReplayDataFailsBeforeHttp(String missing) {
    var payload =
        missing.equals("payload")
            ? null
            : new OpaquePayload(
                OpenAiResponsesMetadata.PROVIDER,
                OpenAiResponsesMetadata.ENCRYPTED_CONTENT_KIND,
                "opaque");
    Map<String, String> attributes =
        missing.equals("item id")
            ? Map.of()
            : Map.of(OpenAiResponsesMetadata.ITEM_ID_ATTRIBUTE, "rs_fixture");
    var assistant =
        AssistantMessage.builder().part(new ReasoningPart(null, null, payload, attributes)).build();
    var options = options();
    var prompt = new Prompt(List.of(new UserMessage("fixture"), assistant), options);
    assertThatThrownBy(() -> model(options).call(prompt))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(missing);
    assertThat(requests).isEmpty();
  }

  private OpenAiResponsesChatOptions controlledOptions(
      dev.mikoto2000.rei.core.stagnation.RunExecutionContext execution,
      OpenAiResponsesChatOptions defaults) {
    var exchange = protocolStore.newExchange(execution.runContext());
    var previous =
        defaults
            .getToolContext()
            .get(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY);
    if (previous
            instanceof dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange original
        && original.observer() != null) exchange = exchange.withObserver(original.observer());
    var context = new java.util.HashMap<>(defaults.getToolContext());
    context.put(
        dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY, exchange);
    context.put(
        dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY,
        new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Origin(
            exchange.ownerKey(), endpoint(defaults).fingerprint()));
    context.put(ResponsesReasoningReplay.OWNER_CONTEXT_KEY, exchange.ownerKey());
    return defaults.mutate().toolContext(null).toolContext(context).build();
  }

  private OpenAiResponsesChatOptions options() {
    var callback =
        new ToolCallback() {
          @Override
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("fixture_tool")
                .description("fixture")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
          }

          @Override
          public String call(String input) {
            executions.incrementAndGet();
            return "fixture result";
          }
        };
    return OpenAiResponsesChatOptions.builder()
        .baseUrl(baseUrl)
        .apiKey("fixture-secret")
        .model(modelName)
        .maxOutputTokens(64)
        .maxRetries(0)
        .toolContext(protocolContext())
        .toolCallbacks(callback)
        .build();
  }

  private ResolvedLlmEndpoint endpoint(OpenAiResponsesChatOptions options) {
    return new ResolvedLlmEndpoint(
        endpointId,
        generation,
        LlmApiType.RESPONSES,
        options.getBaseUrl(),
        "fixture-credential",
        options.getModel(),
        new LlmCapabilities(replayMode, 128),
        options.getMaxOutputTokens(),
        null,
        Duration.ofMinutes(5));
  }

  private LlmModelProvider nativeProvider(LlmProperties properties) {
    var provider =
        LlmModelProvider.forResponsesFixture(
            org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class),
            properties,
            httpCustomizers());
    var beans =
        new org.springframework.beans.factory.support.StaticListableBeanFactory(
            Map.of("payloads", payloads()));
    provider.configureModelMessagePayloads(
        beans.getBeanProvider(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.class));
    return provider;
  }

  private LlmProperties nativeProperties() {
    var source =
        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
            Map.ofEntries(
                Map.entry("rei.llm.features.chat.api", "responses"),
                Map.entry("rei.llm.features.chat.base-url", "https://fixture.invalid"),
                Map.entry("rei.llm.features.chat.api-key", "fixture-secret"),
                Map.entry("rei.llm.features.chat.model", "fixture-model"),
                Map.entry("rei.llm.features.chat.max-output-tokens", "41"),
                Map.entry("rei.llm.features.chat.temperature", "0.2"),
                Map.entry(
                    "rei.llm.features.chat.reasoning-replay",
                    replayMode.name().toLowerCase(java.util.Locale.ROOT)),
                Map.entry("rei.llm.features.chat.max-tools", "16"),
                Map.entry("rei.llm.features.chat.endpoint-generation", "7"),
                Map.entry("rei.llm.features.chat.supported-settings", "temperature")));
    var properties =
        new org.springframework.boot.context.properties.bind.Binder(source)
            .bindOrCreate("rei.llm", LlmProperties.class);
    return properties;
  }

  private ResponsesAdapter model(OpenAiResponsesChatOptions options) {
    return ResponsesAdapter.create(endpoint(options), options, httpCustomizers());
  }

  private List<org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer>
      httpCustomizers() {
    return List.of(builder -> {
      transportBuilds.incrementAndGet();
      builder.interceptor(chain -> {
        var buffer = new okio.Buffer();
        chain.request().body().writeTo(buffer);
        requests.add(new ObjectMapper().readTree(buffer.readByteArray()));
        if (timeoutFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0)
          throw new java.net.SocketTimeoutException("PRIVATE-timeout-fixture");
        var reply = replies.isEmpty() ? done() : replies.removeFirst();
        var fixtureReply = new okhttp3.Response.Builder()
            .request(chain.request())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(httpStatuses.isEmpty() ? 200 : httpStatuses.removeFirst())
            .message("fixture")
            .body(okhttp3.ResponseBody.create(reply, okhttp3.MediaType.get(contentType)));
        if (retryHint != null) fixtureReply.header("X-Should-Retry", retryHint);
        return fixtureReply.build();
      });
    });
  }

  private String done() {
    return response(
        "resp_done",
        """
        [{"type":"message","id":"msg_done","role":"assistant","status":"completed","phase":"final_answer",
          "content":[{"type":"output_text","text":"done","annotations":[]}]}]
        """);
  }

  private String reasoningDone() {
    return response(
        "resp_bound",
        """
        [{"type":"reasoning","id":"rs_bound","summary":[],"encrypted_content":"opaque-bound","status":"completed"},
         {"type":"message","id":"msg_bound","role":"assistant","status":"completed","phase":"final_answer",
          "content":[{"type":"output_text","text":"done","annotations":[]}]}]
        """);
  }

  private String response(String id, String output) {
    String raw =
        """
        {"id":"%s","object":"response","created_at":0,"status":"completed","model":"fixture-model",
         "output":%s,"usage":{"input_tokens":3,"output_tokens":2,"total_tokens":5,
          "input_tokens_details":{"cached_tokens":1},"output_tokens_details":{"reasoning_tokens":1}}}
        """
            .formatted(id, output);
    if (replayMode == ReasoningReplayMode.PLAINTEXT)
      raw =
          raw.replaceAll(
              "\"encrypted_content\":\"([^\"]*)\"",
              "\"content\":[{\"type\":\"reasoning_text\",\"text\":\"PRIVATE-plain-$1\"}]");
    return raw;
  }

  private String sse(String json) throws Exception {
    var mapper = new ObjectMapper();
    var terminal = mapper.readTree(json);
    var created = (com.fasterxml.jackson.databind.node.ObjectNode) terminal.deepCopy();
    created.put("status", "in_progress");
    created.set("output", mapper.createArrayNode());
    created.remove("usage");
    var stream = new StringBuilder();
    int sequence = 0;
    stream.append(event("response.created", sequence++, "\"response\":" + created));
    int index = 0;
    for (var item : terminal.path("output")) {
      if ("reasoning".equals(item.path("type").asText()) && item.path("content").isArray()) {
        String text = item.path("content").get(0).path("text").asText();
        for (String delta :
            List.of(text.substring(0, text.length() / 2), text.substring(text.length() / 2)))
          stream.append(
              event(
                  "response.reasoning_text.delta",
                  sequence++,
                  "\"output_index\":"
                      + index
                      + ",\"item_id\":\""
                      + item.path("id").asText()
                      + "\",\"content_index\":0,\"delta\":"
                      + mapper.writeValueAsString(delta)));
        stream.append(
            event(
                "response.reasoning_text.done",
                sequence++,
                "\"output_index\":"
                    + index
                    + ",\"item_id\":\""
                    + item.path("id").asText()
                    + "\",\"content_index\":0,\"text\":"
                    + mapper.writeValueAsString(text)));
      }
      stream.append(
          event(
              "response.output_item.done",
              sequence++,
              "\"output_index\":" + index++ + ",\"item\":" + item));
    }
    stream.append(event("response.completed", sequence, "\"response\":" + terminal));
    return stream.toString();
  }

  private String event(String type, int sequence, String fields) {
    return "event: "
        + type
        + "\ndata: {\"type\":\""
        + type
        + "\",\"sequence_number\":"
        + sequence
        + ","
        + fields
        + "}\n\n";
  }
}
