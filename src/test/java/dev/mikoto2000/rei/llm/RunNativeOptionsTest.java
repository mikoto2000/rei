package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.modeloptions.ModelOptions;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

class RunNativeOptionsTest {
  @ParameterizedTest @EnumSource(LlmApiType.class)
  void capturesGenerationWithoutCredentialsOrRuntimeContext(LlmApiType api) {
    ToolCallingChatOptions source = api == LlmApiType.CHAT_COMPLETIONS
        ? OpenAiChatOptions.builder().model("model").apiKey("PRIVATE_CANARY")
            .temperature(0.7).topP(0.3).maxCompletionTokens(1024).reasoningEffort("low")
            .serviceTier("default").toolContext(Map.of("PRIVATE_CONTEXT", new Object())).build()
        : OpenAiResponsesChatOptions.builder().model("model").apiKey("PRIVATE_CANARY")
            .temperature(0.7).topP(0.3).maxOutputTokens(1024).reasoningEffort("low")
            .serviceTier("default").toolContext(Map.of("PRIVATE_CONTEXT", new Object())).build();
    var generation = RunNativeOptions.generation(source);
    assertThat(generation).containsEntry("temperature", 0.7).containsEntry("top_p", 0.3)
        .containsEntry("reasoning_effort", "low").containsEntry("service_tier", "default")
        .containsEntry(api == LlmApiType.CHAT_COMPLETIONS
            ? "max_completion_tokens" : "max_output_tokens", 1024);
    assertThat(generation.toString()).doesNotContain("PRIVATE", "apiKey", "credential");
  }

  @ParameterizedTest @EnumSource(LlmApiType.class)
  void reconstructsOnlyTheAcceptedGenerationAndSafeContext(LlmApiType api) {
    var connection = connection(api, Map.of("temperature", 0.7, "top_p", 0.3,
        api == LlmApiType.CHAT_COMPLETIONS ? "max_completion_tokens" : "max_output_tokens", 1024));
    var options = RunNativeOptions.options(connection);
    assertThat(options.getModel()).isEqualTo("model");
    assertThat(options.getTemperature()).isEqualTo(0.7);
    assertThat(options.getTopP()).isEqualTo(0.3);
    assertThat(ModelOptions.outputLimit(options)).isEqualTo(1024);
    assertThat(options.getToolContext()).containsEntry(ModelOptions.RUN_CONNECTION_CONTEXT_KEY, connection)
        .containsEntry(ModelOptions.ENDPOINT_CONTEXT_KEY, connection.endpoint());
    if (options instanceof OpenAiChatOptions chat) assertThat(chat.getApiKey()).isNull();
    else assertThat(((OpenAiResponsesChatOptions) options).getApiKey()).isNull();
  }

  @ParameterizedTest @EnumSource(LlmApiType.class)
  void cannotSilentlyDropAnOutputParameterFromAnotherApi(LlmApiType api) {
    var field = api == LlmApiType.CHAT_COMPLETIONS ? "max_output_tokens" : "max_completion_tokens";
    assertThatThrownBy(() -> RunNativeOptions.options(connection(api, Map.of(field, 1024))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
  }

  private RunLlmConnection connection(LlmApiType api, Map<String, Object> generation) {
    return new RunLlmConnection("local", true, LlmProvider.OPENAI_COMPATIBLE,
        LlmAuthentication.NONE, null, null,
        new ResolvedLlmEndpoint("local", 1, api, "http://local.invalid/v1", "local", "model",
            new LlmCapabilities(ReasoningReplayMode.NONE, 36, Set.of()), 1024, 0.7,
            Duration.ofSeconds(30)), Map.of(), generation);
  }
}
