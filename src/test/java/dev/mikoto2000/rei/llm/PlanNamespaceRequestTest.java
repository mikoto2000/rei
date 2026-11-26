package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Actual SDK wire fixtures; the interceptor returns before DNS or public HTTP. */
class PlanNamespaceRequestTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void planGroupsOnlySuppliedFunctionsInReiNamespaceWithoutExecutingThem(boolean streaming)
      throws Exception {
    try (var fixture = new PlanResponsesRequestTest.Fixture()) {
      var pin = pin(fixture);
      var executions = new AtomicInteger();
      var callback =
          new ToolCallback() {
            public ToolDefinition getToolDefinition() {
              return ToolDefinition.builder()
                  .name("searchTools")
                  .description("Search locally registered Rei tools")
                  .inputSchema(
                      """
                      {"type":"object","properties":{"query":{"type":"string"}},
                       "required":["query"],"additionalProperties":false}
                      """)
                  .build();
            }

            public String call(String input) {
              executions.incrementAndGet();
              return "[]";
            }
          };
      var context = new java.util.HashMap<>(fixture.options().getToolContext());
      context.put(dev.mikoto2000.rei.modeloptions.ModelOptions.RUN_CONNECTION_CONTEXT_KEY, pin);
      var options =
          ((OpenAiResponsesChatOptions) RunNativeOptions.options(pin))
              .mutate()
              .toolContext(context)
              .toolCallbacks(List.of(callback))
              .build();
      var model = ResponsesAdapter.createForRun(pin, options, fixture.client);
      assertThatCode(
              () -> {
                var prompt = new Prompt("fixture", options);
                if (streaming) model.stream(prompt).collectList().block(Duration.ofSeconds(5));
                else model.call(prompt);
              })
          .doesNotThrowAnyException();
      assertThat(fixture.requests).hasSize(1);
      var wire = fixture.requests.getFirst();
      assertThat(wire.path("tools")).hasSize(1);
      assertThat(wire.at("/tools/0/type").asText()).isEqualTo("namespace");
      assertThat(wire.at("/tools/0/name").asText()).isEqualTo("rei");
      assertThat(wire.at("/tools/0/tools")).hasSize(1);
      assertThat(wire.at("/tools/0/tools/0/type").asText()).isEqualTo("function");
      assertThat(wire.at("/tools/0/tools/0/name").asText()).isEqualTo("searchTools");
      assertThat(wire.at("/tools/0/tools/0/description").asText())
          .isEqualTo(callback.getToolDefinition().description());
      assertThat(wire.at("/tools/0/tools/0/parameters"))
          .isEqualTo(
              new com.fasterxml.jackson.databind.ObjectMapper()
                  .readTree(callback.getToolDefinition().inputSchema()));
      assertThat(wire.toString()).doesNotContain("tool_search", "additional_tools");
      assertThat(wire.path("stream").asBoolean()).isTrue();
      assertThat(wire.path("store").asBoolean(true)).isFalse();
      assertThat(executions).hasValue(0);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void namespaceCallAndResultSurviveCanonicalStorageAndTheNextRequest(boolean streaming)
      throws Exception {
    try (var fixture = new PlanResponsesRequestTest.Fixture()) {
      var pin = pin(fixture);
      var context = new java.util.HashMap<>(fixture.options().getToolContext());
      context.put(dev.mikoto2000.rei.modeloptions.ModelOptions.RUN_CONNECTION_CONTEXT_KEY, pin);
      var options =
          ((OpenAiResponsesChatOptions) RunNativeOptions.options(pin))
              .mutate()
              .toolContext(context)
              .toolCallbacks(
                  List.of(
                      new ToolCallback() {
                        public ToolDefinition getToolDefinition() {
                          return ToolDefinition.builder()
                              .name("searchTools")
                              .description("Search locally registered Rei tools")
                              .inputSchema("{\"type\":\"object\"}")
                              .build();
                        }

                        public String call(String input) {
                          throw new AssertionError("The adapter must not execute callbacks");
                        }
                      }))
              .build();
      var done = fixture.reply;
      var call =
          java.util.Map.of(
              "type", "function_call",
              "id", "fc_fixture",
              "call_id", "call_fixture",
              "name", "searchTools",
              "namespace", "rei",
              "arguments", "{\"query\":\"fixture\"}",
              "status", "completed");
      fixture.reply =
          PlanResponsesRequestTest.Fixture.event(
              "response.completed",
              "\"response\":"
                  + new com.fasterxml.jackson.databind.ObjectMapper()
                      .writeValueAsString(
                          java.util.Map.of(
                              "id", "resp_tools",
                              "object", "response",
                              "created_at", 0,
                              "status", "completed",
                              "model", "fixture-model",
                              "output", List.of(call),
                              "usage",
                                  java.util.Map.of(
                                      "input_tokens", 3, "output_tokens", 2, "total_tokens", 5))));
      var model = ResponsesAdapter.createForRun(pin, options, fixture.client);
      var response =
          new java.util.concurrent.atomic.AtomicReference<
              org.springframework.ai.chat.model.ChatResponse>();
      assertThatCode(() -> response.set(invoke(model, new Prompt("fixture", options), streaming)))
          .doesNotThrowAnyException();
      var output = response.get().getResult().getOutput();
      var codec = new dev.mikoto2000.rei.modelmessage.ModelMessageCodec();
      var restored =
          (org.springframework.ai.chat.messages.AssistantMessage)
              codec.decode(codec.encode(output));
      assertThat(restored.getParts()).hasSize(1);
      var part =
          (org.springframework.ai.chat.messages.part.ToolCallPart) restored.getParts().getFirst();
      assertThat(part.attributes()).containsEntry("rei.responses.tool-namespace", "rei");
      assertThat(part.attributes())
          .containsEntry(
              org.springframework.ai.openai.responses.OpenAiResponsesMetadata.ITEM_ID_ATTRIBUTE,
              "fc_fixture");
      assertThat(part.toolCall().id()).isEqualTo("call_fixture");
      assertThat(part.toolCall().name()).isEqualTo("searchTools");
      assertThat(part.toolCall().arguments()).isEqualTo("{\"query\":\"fixture\"}");
      fixture.reply = done;
      var result =
          org.springframework.ai.chat.messages.ToolResponseMessage.builder()
              .responses(
                  List.of(
                      new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                          "call_fixture", "searchTools", "[]")))
              .build();
      invoke(
          model,
          new Prompt(
              List.of(
                  new org.springframework.ai.chat.messages.UserMessage("fixture"),
                  restored,
                  result),
              options),
          streaming);
      assertThat(fixture.requests).hasSize(2);
      var input = fixture.requests.getLast().path("input");
      var replayed = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
      var results = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
      input.forEach(item -> {
        if (item.path("type").asText().equals("function_call")) replayed.add(item);
        if (item.path("type").asText().equals("function_call_output")) results.add(item);
      });
      assertThat(replayed)
          .singleElement()
          .satisfies(item -> {
            assertThat(item.path("namespace").asText()).isEqualTo("rei");
            assertThat(item.path("name").asText()).isEqualTo("searchTools");
            assertThat(item.path("call_id").asText()).isEqualTo("call_fixture");
            assertThat(item.path("id").asText()).isEqualTo("fc_fixture");
            assertThat(item.path("arguments").asText()).isEqualTo("{\"query\":\"fixture\"}");
          });
      assertThat(results)
          .singleElement()
          .satisfies(item -> {
            assertThat(item.path("namespace").asText()).isEqualTo("rei");
            assertThat(item.path("call_id").asText()).isEqualTo("call_fixture");
            assertThat(item.path("output").asText()).isEqualTo("[]");
          });
    }
  }

  private static org.springframework.ai.chat.model.ChatResponse invoke(
      org.springframework.ai.chat.model.ChatModel model, Prompt prompt, boolean streaming) {
    if (!streaming) return model.call(prompt);
    var result =
        new java.util.concurrent.atomic.AtomicReference<
            org.springframework.ai.chat.model.ChatResponse>();
    new org.springframework.ai.chat.model.MessageAggregator()
        .aggregate(model.stream(prompt), result::set)
        .blockLast(Duration.ofSeconds(5));
    return result.get();
  }

  private static RunLlmConnection pin(PlanResponsesRequestTest.Fixture fixture) {
    var source = fixture.pin;
    var capabilities = new java.util.HashMap<>(source.capabilities());
    capabilities.put(
        LlmEndpointCapabilities.Capability.FUNCTION_CALLING,
        LlmEndpointCapabilities.Support.SUPPORTED);
    capabilities.put(
        LlmEndpointCapabilities.Capability.TOOL_NAMESPACE,
        LlmEndpointCapabilities.Support.SUPPORTED);
    return new RunLlmConnection(
        source.profileId(),
        source.named(),
        source.provider(),
        source.authentication(),
        source.accountRef(),
        source.accountRegistration(),
        source.endpoint(),
        capabilities,
        source.generation(),
        source.transport());
  }
}
