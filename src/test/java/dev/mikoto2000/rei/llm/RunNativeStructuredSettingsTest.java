package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

class RunNativeStructuredSettingsTest {
  private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}}}";

  @ParameterizedTest @EnumSource(LlmApiType.class)
  void capturesStructuredFormatWithoutLosingItsSchemaOrStrictness(LlmApiType api) {
    var format = OpenAiChatModel.ResponseFormat.builder()
        .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA).jsonSchema(SCHEMA).strict(false).build();
    ToolCallingChatOptions source = api == LlmApiType.CHAT_COMPLETIONS
        ? OpenAiChatOptions.builder().model("model").responseFormat(format).build()
        : OpenAiResponsesChatOptions.builder().model("model").responseFormat(format).build();
    assertThat(RunNativeOptions.generation(source)).containsEntry("response_format", formatSettings());
  }

  @ParameterizedTest @EnumSource(LlmApiType.class)
  void restoresTheTypedNativeFormat(LlmApiType api) {
    var options = RunNativeOptions.options(connection(api));
    var format = options instanceof OpenAiChatOptions chat ? chat.getResponseFormat()
        : ((OpenAiResponsesChatOptions) options).getResponseFormat();
    assertThat(format).isNotNull();
    assertThat(format.getType()).isEqualTo(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA);
    assertThat(format.getJsonSchema()).isEqualTo(SCHEMA);
    assertThat(format.getStrict()).isFalse();
  }

  @ParameterizedTest @EnumSource(LlmApiType.class)
  void refusesUnsupportedRawBodyInsteadOfDroppingOrPersistingIt(LlmApiType api) {
    ToolCallingChatOptions source = api == LlmApiType.CHAT_COMPLETIONS
        ? OpenAiChatOptions.builder().model("model").extraBody(Map.of("PRIVATE_CANARY", "value")).build()
        : OpenAiResponsesChatOptions.builder().model("model").extraBody(Map.of("PRIVATE_CANARY", "value")).build();
    assertThatThrownBy(() -> RunNativeOptions.generation(source))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("extra_body")
        .hasMessageNotContaining("PRIVATE_CANARY");
  }

  private Map<String, Object> formatSettings() {
    return Map.of("type", "json_schema", "json_schema", SCHEMA, "strict", false);
  }

  private RunLlmConnection connection(LlmApiType api) {
    return new RunLlmConnection("local", true, LlmProvider.OPENAI_COMPATIBLE,
        LlmAuthentication.NONE, null, null,
        new ResolvedLlmEndpoint("local", 1, api, "http://local.invalid/v1", "local", "model",
            new LlmCapabilities(ReasoningReplayMode.NONE, 36, Set.of()), null, null,
            Duration.ofSeconds(30)), Map.of(), Map.of("response_format", formatSettings()));
  }
}
