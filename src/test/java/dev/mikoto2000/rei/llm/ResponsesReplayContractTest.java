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
  @org.junit.jupiter.params.provider.ValueSource(strings = {"provider", "kind"})
  void foreignPayloadKindsAreNotSilentlyDroppedOrTreatedAsEncryptedReasoning(String changed) {
    var payload = new OpaquePayload(changed.equals("provider") ? "foreign-provider" : OpenAiResponsesMetadata.PROVIDER,
        changed.equals("kind") ? "foreign-signature" : OpenAiResponsesMetadata.ENCRYPTED_CONTENT_KIND, "private-fixture-payload");
    var assistant = AssistantMessage.builder().part(new ReasoningPart(null, null, payload,
        Map.of(OpenAiResponsesMetadata.ITEM_ID_ATTRIBUTE, "rs_fixture"))).build();
    var options = options();
    var prompt = new Prompt(List.of(new UserMessage("fixture"), assistant), options);
    assertThatThrownBy(() -> model(options).call(prompt)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("payload").hasMessageNotContaining("private-fixture-payload").hasNoCause();
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

  @Test
  void encryptedRequestsRequireAnExplicitOwnerBeforeAnyHttp() {
    owner = null;
    var options = options();
    assertThatThrownBy(() -> model(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("owner");
    assertThat(requests).isEmpty();
  }

  @Test
  void anotherOwnerCannotReplayAResponseFromTheSameEndpoint() {
    replies.add(reasoningDone());
    var options = options();
    var assistant = model(options).call(new Prompt("fixture", options)).getResult().getOutput();
    owner = "foreign-owner";
    var changed = options();
    assertThatThrownBy(() -> model(changed).call(new Prompt(List.of(new UserMessage("fixture"), assistant), changed)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("owner")
        .hasMessageNotContaining("foreign-owner");
    assertThat(requests).hasSize(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"endpoint", "generation", "model", "url", "mode"})
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
    assertThatThrownBy(() -> model(target).call(new Prompt(List.of(new UserMessage("fixture"), assistant), target)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("replay");
    assertThat(requests).hasSize(1);
  }

  @Test
  void anUnboundReasoningPartCannotClaimCurrentEndpointProvenance() {
    var part = new ReasoningPart(null, null, new OpaquePayload(OpenAiResponsesMetadata.PROVIDER,
        OpenAiResponsesMetadata.ENCRYPTED_CONTENT_KIND, "opaque"),
        Map.of(OpenAiResponsesMetadata.ITEM_ID_ATTRIBUTE, "rs_fixture"));
    var assistant = AssistantMessage.builder().part(part).build();
    var options = options();
    assertThatThrownBy(() -> model(options).call(new Prompt(List.of(new UserMessage("fixture"), assistant), options)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("origin");
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
    assertThat(requests.getLast().path("input").get(1).path("encrypted_content").asText()).isEqualTo("opaque-bound");
    assertThat(assistant.getParts()).containsExactlyElementsOf(originalParts);
  }

  @Test
  void existingToolDispatchReplaysEveryEncryptedPartIdAndPhaseWithoutRewriting() {
    replies.add(response("resp_tools", """
        [{"type":"reasoning","id":"rs_one","summary":[],"encrypted_content":"opaque-one+/=","status":"completed"},
         {"type":"function_call","id":"fc_one","call_id":"call_one","name":"fixture_tool","arguments":"{}","status":"completed"},
         {"type":"message","id":"msg_work","role":"assistant","status":"completed","phase":"commentary",
          "content":[{"type":"output_text","text":"working","annotations":[]}]},
         {"type":"reasoning","id":"rs_two","summary":[],"encrypted_content":"opaque-two+/=","status":"completed"},
         {"type":"function_call","id":"fc_two","call_id":"call_two","name":"fixture_tool","arguments":"{}","status":"completed"}]
        """));
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
    assertThat(types).containsExactly("message", "reasoning", "function_call", "message", "reasoning",
        "function_call", "function_call_output", "function_call_output");
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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"payload", "item id"})
  void missingRequiredEncryptedReplayDataFailsBeforeHttp(String missing) {
    var payload = missing.equals("payload") ? null : new OpaquePayload(
        OpenAiResponsesMetadata.PROVIDER, OpenAiResponsesMetadata.ENCRYPTED_CONTENT_KIND, "opaque");
    Map<String, String> attributes = missing.equals("item id") ? Map.of()
        : Map.of(OpenAiResponsesMetadata.ITEM_ID_ATTRIBUTE, "rs_fixture");
    var assistant = AssistantMessage.builder().part(new ReasoningPart(null, null, payload, attributes)).build();
    var options = options();
    var prompt = new Prompt(List.of(new UserMessage("fixture"), assistant), options);
    assertThatThrownBy(() -> model(options).call(prompt)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(missing);
    assertThat(requests).isEmpty();
  }

  private OpenAiResponsesChatOptions options() {
    var callback = new ToolCallback() {
      @Override public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name("fixture_tool").description("fixture")
            .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
      }
      @Override public String call(String input) { executions.incrementAndGet(); return "fixture result"; }
    };
    return OpenAiResponsesChatOptions.builder().baseUrl(baseUrl)
        .apiKey("fixture-secret").model(modelName).maxOutputTokens(64).maxRetries(0)
        .toolContext(owner == null ? Map.of() : Map.of("rei.llm.replay-owner", owner))
        .toolCallbacks(callback).build();
  }

  private ResponsesAdapter model(OpenAiResponsesChatOptions options) {
    var endpoint = new ResolvedLlmEndpoint(endpointId, generation, LlmApiType.RESPONSES, options.getBaseUrl(),
        "fixture-credential", options.getModel(), new LlmCapabilities(replayMode, 128),
        options.getMaxOutputTokens(), null, Duration.ofMinutes(5));
    return ResponsesAdapter.create(endpoint, options, List.of(builder -> builder.interceptor(chain -> {
      var buffer = new okio.Buffer();
      chain.request().body().writeTo(buffer);
      requests.add(new ObjectMapper().readTree(buffer.readByteArray()));
      var reply = replies.isEmpty() ? done() : replies.removeFirst();
      return new okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
          .code(200).message("fixture").body(okhttp3.ResponseBody.create(reply,
              okhttp3.MediaType.get("application/json"))).build();
    })));
  }

  private String done() {
    return response("resp_done", """
        [{"type":"message","id":"msg_done","role":"assistant","status":"completed","phase":"final_answer",
          "content":[{"type":"output_text","text":"done","annotations":[]}]}]
        """);
  }

  private String reasoningDone() {
    return response("resp_bound", """
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
        """.formatted(id, output);
  }
}
