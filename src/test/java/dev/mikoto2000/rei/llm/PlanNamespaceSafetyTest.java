package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Malformed native calls are refused before an application could execute a callback. */
class PlanNamespaceSafetyTest {
  static Stream<Arguments> invalidCalls() {
    return Stream.of(
        Arguments.of(null, "searchTools", "call_fixture", "{}"),
        Arguments.of("", "searchTools", "call_fixture", "{}"),
        Arguments.of("foreign", "searchTools", "call_fixture", "{}"),
        Arguments.of("rei", "rei.searchTools", "call_fixture", "{}"),
        Arguments.of("rei", "tool_search", "call_fixture", "{}"),
        Arguments.of("rei", "otherTool", "call_fixture", "{}"),
        Arguments.of("rei", "searchTools", "", "{}"),
        Arguments.of("rei", "searchTools", "bad\nid", "{}"),
        Arguments.of("rei", "searchTools", "bad id", "{}"),
        Arguments.of("rei", "searchTools", "x".repeat(513), "{}"),
        Arguments.of("rei", "searchTools", "call_fixture", "{"),
        Arguments.of("rei", "searchTools", "call_fixture", "[]"));
  }

  @ParameterizedTest
  @MethodSource("invalidCalls")
  void rejectsMalformedCompletedCalls(String namespace, String name, String id, String arguments)
      throws Exception {
    try (var fixture = new PlanResponsesRequestTest.Fixture()) {
      var pin = PlanNamespaceRequestTest.pin(fixture);
      var options = options(fixture, pin);
      var call = new LinkedHashMap<String, Object>();
      call.put("type", "function_call");
      call.put("id", "fc_fixture");
      call.put("call_id", id);
      call.put("name", name);
      if (namespace != null) call.put("namespace", namespace);
      call.put("arguments", arguments);
      call.put("status", "completed");
      fixture.reply =
          PlanResponsesRequestTest.Fixture.event(
              "response.completed",
              "\"response\":"
                  + new com.fasterxml.jackson.databind.ObjectMapper()
                      .writeValueAsString(
                          Map.of(
                              "id", "resp_fixture",
                              "object", "response",
                              "created_at", 0,
                              "status", "completed",
                              "model", "fixture-model",
                              "output", List.of(call),
                              "usage",
                                  Map.of(
                                      "input_tokens", 3, "output_tokens", 2, "total_tokens", 5))));
      var model = ResponsesAdapter.createForRun(pin, options, fixture.client);
      assertThatThrownBy(() -> model.call(new Prompt("fixture", options)))
          .isInstanceOf(ResponsesTransportException.class)
          .hasMessageContaining("INVALID_RESPONSE");
      assertThat(fixture.requests).hasSize(1);
    }
  }

  @Test
  void refusesUnconfirmedIndividualFunctionChoiceBeforeHttp() throws Exception {
    try (var fixture = new PlanResponsesRequestTest.Fixture()) {
      var pin = PlanNamespaceRequestTest.pin(fixture);
      var options = options(fixture, pin);
      var model = ResponsesAdapter.createForRun(pin, options, fixture.client);
      var forced =
          options.mutate().toolChoice("{\"type\":\"function\",\"name\":\"searchTools\"}").build();
      assertThatThrownBy(() -> model.call(new Prompt("fixture", forced)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("tool_choice");
      assertThat(fixture.requests).isEmpty();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"auto", "none", "required"})
  void ordinaryToolChoiceRetainsItsExactWireValue(String choice) throws Exception {
    try (var fixture = new PlanResponsesRequestTest.Fixture()) {
      var pin = PlanNamespaceRequestTest.pin(fixture);
      var options = options(fixture, pin);
      var model = ResponsesAdapter.createForRun(pin, options, fixture.client);
      model.call(new Prompt("fixture", options.mutate().toolChoice(choice).build()));
      assertThat(fixture.requests)
          .singleElement()
          .satisfies(wire -> {
            assertThat(wire.path("tool_choice").asText()).isEqualTo(choice);
            assertThat(wire.at("/tools/0/name").asText()).isEqualTo("rei");
          });
    }
  }

  private static OpenAiResponsesChatOptions options(
      PlanResponsesRequestTest.Fixture fixture, RunLlmConnection pin) {
    var context = new java.util.HashMap<>(fixture.options().getToolContext());
    context.put(dev.mikoto2000.rei.modeloptions.ModelOptions.RUN_CONNECTION_CONTEXT_KEY, pin);
    return ((OpenAiResponsesChatOptions) RunNativeOptions.options(pin))
        .mutate()
        .toolContext(context)
        .toolCallbacks(
            List.of(
                new ToolCallback() {
                  public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder()
                        .name("searchTools")
                        .description("Local fixture tool")
                        .inputSchema("{\"type\":\"object\"}")
                        .build();
                  }

                  public String call(String input) {
                    throw new AssertionError("No malformed call may execute a callback");
                  }
                }))
        .build();
  }
}
