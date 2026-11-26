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
      var source = fixture.pin;
      var capabilities = new java.util.HashMap<>(source.capabilities());
      capabilities.put(
          LlmEndpointCapabilities.Capability.FUNCTION_CALLING,
          LlmEndpointCapabilities.Support.SUPPORTED);
      capabilities.put(
          LlmEndpointCapabilities.Capability.TOOL_NAMESPACE,
          LlmEndpointCapabilities.Support.SUPPORTED);
      var pin =
          new RunLlmConnection(
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
      assertThatCode(() -> {
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
}
