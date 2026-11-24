package dev.mikoto2000.rei.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

class NativeClassificationRuleOptionsTest {
  @ParameterizedTest
  @ValueSource(ints = {41, 16000})
  void structuredRuleExtractionUsesNativeSettingsWithinTheProviderBound(int providerLimit)
      throws Exception {
    var model = mock(ChatModel.class);
    var base =
        OpenAiResponsesChatOptions.builder()
            .model("native-rules")
            .maxOutputTokens(providerLimit)
            .build();
    String schema =
        "{\"name\":\"fixture_rule\",\"schema\":{\"type\":\"object\",\"properties\":{\"rule\":{\"type\":\"string\"}},\"required\":[\"rule\"],\"additionalProperties\":false}}";
    when(model.call(any(Prompt.class)))
        .thenAnswer(invocation -> {
          Prompt prompt = invocation.getArgument(0);
          assertThat(prompt.getOptions()).isInstanceOf(OpenAiResponsesChatOptions.class);
          var sent = (OpenAiResponsesChatOptions) prompt.getOptions();
          assertThat(sent.getMaxOutputTokens()).isEqualTo(Math.min(providerLimit, 512));
          assertThat(sent.getResponseFormat().getJsonSchema()).isEqualTo(schema);
          assertThat(sent.getResponseFormat().getStrict()).isTrue();
          assertThat(sent.getToolCallbacks()).isEmpty();
          assertThat(sent.getToolChoice()).isNull();
          assertThat(sent.getToolContext().get("rei.llm.replay-owner"))
              .asString()
              .startsWith("feature:");
          return new ChatResponse(
              List.of(new Generation(new AssistantMessage("{\"rule\":\"fixture\"}"))));
        });
    Supplier<ChatModel> models = () -> model;
    Supplier<OpenAiResponsesChatOptions> options = () -> base;
    // The injected Supplier is erased at runtime; native options must not be cast to Chat options.
    var extractor =
        LlmClassificationRuleModel.class
            .getConstructor(Supplier.class, Supplier.class, int.class)
            .newInstance(models, options, 512);
    assertThat(extractor.propose("untrusted evidence", schema)).isEqualTo("{\"rule\":\"fixture\"}");
    assertThat(base.getMaxOutputTokens()).isEqualTo(providerLimit);
    assertThat(base.getResponseFormat()).isNull();
  }
}
