package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.modeloptions.ModelOptions;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

class RunLlmConnectionRequestGuardTest {
  static Stream<Arguments> overrides() {
    return Arrays.stream(LlmApiType.values()).flatMap(api -> Stream.of(
        "base_url", "api_key", "credential", "custom_headers", "organization")
        .map(field -> Arguments.of(api, field)));
  }

  @ParameterizedTest @MethodSource("overrides")
  void requestOptionsCannotOverrideTheAcceptedAudienceOrCredentials(LlmApiType api, String field) {
    var calls = new AtomicInteger();
    var model = model(calls);
    assertThatThrownBy(() -> model.call(new Prompt("request", options(api, field, true))))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("override");
    assertThat(calls).hasValue(0);
  }

  @ParameterizedTest @EnumSource(LlmApiType.class)
  void protocolEndpointContextCannotDisagreeWithTheAcceptedConnection(LlmApiType api) {
    var calls = new AtomicInteger();
    var options = options(api, "none", true);
    var context = new java.util.HashMap<>(options.getToolContext());
    context.put(ModelOptions.ENDPOINT_CONTEXT_KEY, endpoint(api, "foreign"));
    var changed = options.mutate().toolContext(null).toolContext(context).build();
    assertThatThrownBy(() -> model(calls).call(new Prompt("request", changed)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("endpoint");
    assertThat(calls).hasValue(0);
  }

  @ParameterizedTest @EnumSource(LlmApiType.class)
  void missingProtocolEndpointIsRejectedBeforeTransportResolution(LlmApiType api) {
    var calls = new AtomicInteger();
    assertThatThrownBy(() -> model(calls).call(new Prompt("request", options(api, "none", false))))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("endpoint");
    assertThat(calls).hasValue(0);
  }

  private RunLlmConnectionModel model(AtomicInteger calls) {
    return new RunLlmConnectionModel(connection -> {
      calls.incrementAndGet();
      return org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
    }, connection -> {});
  }

  private ToolCallingChatOptions options(LlmApiType api, String field, boolean withEndpoint) {
    var endpoint = endpoint(api, "local");
    var connection = new RunLlmConnection("local", true, LlmProvider.OPENAI_COMPATIBLE,
        LlmAuthentication.NONE, null, null, endpoint, Map.of(), Map.of());
    var context = new java.util.HashMap<String, Object>();
    context.put(ModelOptions.RUN_CONNECTION_CONTEXT_KEY, connection);
    if (withEndpoint) context.put(ModelOptions.ENDPOINT_CONTEXT_KEY, endpoint);
    if (api == LlmApiType.CHAT_COMPLETIONS) {
      var builder = OpenAiChatOptions.builder().model("model").toolContext(context);
      switch (field) {
        case "base_url" -> builder.baseUrl("http://foreign.invalid/v1");
        case "api_key" -> builder.apiKey("PRIVATE_CANARY");
        case "credential" -> builder.credential(com.openai.credential.BearerTokenCredential.create("PRIVATE_CANARY"));
        case "custom_headers" -> builder.customHeaders(Map.of("Authorization", "Bearer PRIVATE_CANARY"));
        case "organization" -> builder.organizationId("foreign");
        default -> {}
      }
      return builder.build();
    }
    var builder = OpenAiResponsesChatOptions.builder().model("model").toolContext(context);
    switch (field) {
      case "base_url" -> builder.baseUrl("http://foreign.invalid/v1");
      case "api_key" -> builder.apiKey("PRIVATE_CANARY");
      case "credential" -> builder.credential(com.openai.credential.BearerTokenCredential.create("PRIVATE_CANARY"));
      case "custom_headers" -> builder.customHeaders(Map.of("Authorization", "Bearer PRIVATE_CANARY"));
      case "organization" -> builder.organizationId("foreign");
      default -> {}
    }
    return builder.build();
  }

  private ResolvedLlmEndpoint endpoint(LlmApiType api, String id) {
    return new ResolvedLlmEndpoint(id, 1, api, "http://" + id + ".invalid/v1", id, "model",
        new LlmCapabilities(ReasoningReplayMode.NONE, 36, Set.of()), null, null,
        Duration.ofSeconds(30));
  }
}
