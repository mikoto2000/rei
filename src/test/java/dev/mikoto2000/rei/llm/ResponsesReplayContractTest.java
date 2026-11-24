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
      boolean streaming, @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    contentType = streaming ? "text/event-stream" : "application/json";
    replies.add(streaming ? sse(reasoningDone()) : reasoningDone());
    var options = options();
    var model = model(options);
    org.springframework.ai.chat.model.ChatResponse response;
    if (streaming) {
      var completed = new java.util.concurrent.atomic.AtomicReference<org.springframework.ai.chat.model.ChatResponse>();
      new org.springframework.ai.chat.model.MessageAggregator().aggregate(
          model.stream(new Prompt("fixture", options)), completed::set).blockLast(Duration.ofSeconds(10));
      response = completed.get();
    } else response = model.call(new Prompt("fixture", options));
    var original = response.getResult().getOutput();
    var codec = new dev.mikoto2000.rei.modelmessage.ModelMessageCodec();
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("messages.db"));
    var store = new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore(source);
    var scope = new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope("fixture-project", directory.toString(), "fixture-conversation");
    var ref = store.save(scope, "fixture-run", "assistant", original);
    var pointer = store.historyReference(scope, ref, "fixture-history");
    var restored = (AssistantMessage) new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore(source)
        .restoreHistory(scope, pointer).orElseThrow();
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
    var defaults = options();
    var options =
        defaults
            .mutate()
            .toolContext(
                Map.of(
                    ResponsesReasoningReplay.OWNER_CONTEXT_KEY,
                    owner,
                    dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY,
                    context))
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
    var defaults = options();
    var guarded =
        defaults
            .mutate()
            .toolContext(
                Map.of(
                    ResponsesReasoningReplay.OWNER_CONTEXT_KEY,
                    owner,
                    dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY,
                    context))
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
  private final AtomicInteger executions = new AtomicInteger();
  private String owner = "fixture-owner";
  private String endpointId = "fixture";
  private long generation = 1;
  private String modelName = "fixture-model";
  private String baseUrl = "https://responses-fixture.invalid/v1";
  private ReasoningReplayMode replayMode = ReasoningReplayMode.ENCRYPTED;
  private String contentType = "application/json";

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
    var defaults = options();
    var requestOptions =
        defaults
            .mutate()
            .toolCallbacks(callbacks)
            .toolContext(
                Map.of(
                    ResponsesReasoningReplay.OWNER_CONTEXT_KEY,
                    owner,
                    dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY,
                    execution))
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
        .toolContext(owner == null ? Map.of() : Map.of("rei.llm.replay-owner", owner))
        .toolCallbacks(callback)
        .build();
  }

  private ResponsesAdapter model(OpenAiResponsesChatOptions options) {
    var endpoint =
        new ResolvedLlmEndpoint(
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
    return ResponsesAdapter.create(
        endpoint,
        options,
        List.of(builder ->
            builder.interceptor(chain -> {
              var buffer = new okio.Buffer();
              chain.request().body().writeTo(buffer);
              requests.add(new ObjectMapper().readTree(buffer.readByteArray()));
              var reply = replies.isEmpty() ? done() : replies.removeFirst();
              return new okhttp3.Response.Builder()
                  .request(chain.request())
                  .protocol(okhttp3.Protocol.HTTP_1_1)
                  .code(200)
                  .message("fixture")
                  .body(okhttp3.ResponseBody.create(reply, okhttp3.MediaType.get(contentType)))
                  .build();
            })));
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
    return """
    {"id":"%s","object":"response","created_at":0,"status":"completed","model":"fixture-model",
     "output":%s,"usage":{"input_tokens":3,"output_tokens":2,"total_tokens":5,
      "input_tokens_details":{"cached_tokens":1},"output_tokens_details":{"reasoning_tokens":1}}}
    """
        .formatted(id, output);
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
    for (var item : terminal.path("output"))
      stream.append(
          event(
              "response.output_item.done",
              sequence++,
              "\"output_index\":" + index++ + ",\"item\":" + item));
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
