package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatModel;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

/** Exercises the pinned SDK's serialized HTTP request, without external traffic. */
class ResponsesTransportContractTest {
  private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
  private final List<String> urls = new CopyOnWriteArrayList<>();
  private String reply = completed();

  @Test
  void toolFreeRequestsOmitBothToolFieldsAndUseOnlyResponsesTokenLimits() {
    var options = options().toolCallbacks(List.of()).toolChoice("none").build();
    var response = transport(options).call(new Prompt("fixture input", options));
    assertThat(response.getResult().getOutput().getText()).isEqualTo("done");
    assertThat(urls).containsExactly("https://responses-fixture.invalid/v1/responses");
    assertThat(requests).hasSize(1);
    var request = requests.getFirst();
    assertThat(request.path("model").asText()).isEqualTo("fixture-model");
    assertThat(request.path("max_output_tokens").asInt()).isEqualTo(64);
    assertThat(request.path("store").asBoolean(true)).isFalse();
    assertThat(options.getToolChoice()).isEqualTo("none");
    assertThat(request.toString()).doesNotContain("fixture-secret");
    for (String field : List.of("tools", "tool_choice", "max_tokens", "max_completion_tokens",
        "previous_response_id", "conversation"))
      assertThat(request.has(field)).as(field).isFalse();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {
    "model", "input", "instructions", "tools", "tool_choice", "store", "previous_response_id",
    "conversation", "max_tokens", "max_completion_tokens", "max_output_tokens", "reasoning",
    "text", "stream", "include", "parallel_tool_calls", "response_format", "messages",
    "functions", "function_call", "chat_template_kwargs", "background", "context_management",
    "truncation"
  })
  void extraBodyCannotOverrideProtectedProtocolFields(String field) {
    var options = options().extraBody(java.util.Map.of(field, "untrusted override")).build();
    assertThatThrownBy(() -> transport(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field)
        .hasMessageNotContaining("untrusted override").hasMessageNotContaining("fixture-secret");
    assertThat(requests).isEmpty();
  }

  @Test
  void rejectsChatCompletionsOptionsBeforeSending() {
    var legacy = org.springframework.ai.openai.OpenAiChatOptions.builder()
        .model("fixture-model").maxTokens(64).build();
    assertThatThrownBy(() -> transport(options().build()).call(new Prompt("fixture", legacy)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Responses native");
    assertThat(requests).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {
    "incomplete", "in_progress", "queued", "cancelled", "unknown"
  })
  void onlyExplicitlyCompletedResponsesAreSuccessful(String status) {
    reply = completed().replaceFirst("\"status\":\"completed\"",
        "\"status\":\"" + status + "\"");
    var options = options().build();
    assertThatThrownBy(() -> transport(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Responses");
  }

  @Test
  void missingResponseStatusDoesNotBecomeCompletedThroughGenerationDefaults() {
    reply = completed().replaceFirst("\"status\":\"completed\",", "");
    var options = options().build();
    assertThatThrownBy(() -> transport(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Responses");
  }

  private OpenAiResponsesChatOptions.Builder options() {
    return OpenAiResponsesChatOptions.builder()
        .baseUrl("https://responses-fixture.invalid/v1")
        .apiKey("fixture-secret").model("fixture-model").maxOutputTokens(64).maxRetries(0);
  }

  private ChatModel transport(OpenAiResponsesChatOptions options) {
    var model = OpenAiResponsesChatModel.builder().options(options)
        .httpClientBuilderCustomizer(builder -> builder.interceptor(chain -> {
          var buffer = new okio.Buffer();
          chain.request().body().writeTo(buffer);
          requests.add(new ObjectMapper().readTree(buffer.readByteArray()));
          urls.add(chain.request().url().toString());
          return new okhttp3.Response.Builder().request(chain.request())
              .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
              .body(okhttp3.ResponseBody.create(reply,
                  okhttp3.MediaType.get("application/json"))).build();
        })).build();
    var endpoint = new ResolvedLlmEndpoint("fixture", 1, LlmApiType.RESPONSES,
        options.getBaseUrl(), "fixture-credential", options.getModel(),
        new LlmCapabilities(ReasoningReplayMode.NONE, 128), options.getMaxOutputTokens(),
        options.getTemperature(), java.time.Duration.ofMinutes(5));
    return new ResponsesAdapter(endpoint, model);
  }

  private String completed() {
    return """
        {"id":"resp_fixture","object":"response","created_at":0,"status":"completed",
         "model":"fixture-model","output":[{"type":"message","id":"msg_fixture",
         "role":"assistant","status":"completed","phase":"final_answer",
         "content":[{"type":"output_text","text":"done","annotations":[]}]}],
         "usage":{"input_tokens":3,"output_tokens":2,"total_tokens":5,
          "input_tokens_details":{"cached_tokens":1},
          "output_tokens_details":{"reasoning_tokens":1}}}
        """;
  }
}
