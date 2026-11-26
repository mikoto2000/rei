package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.modeloptions.ModelOptions;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

class RunLlmConnectionFeatureIsolationTest {
  @ParameterizedTest
  @EnumSource(LlmApiType.class)
  void helperContextDropsTheParentsAcceptedRunConnection(LlmApiType api) {
    var prepared =
        ModelOptions.singleRequest(
            options(api), Map.of(ModelOptions.RUN_CONNECTION_CONTEXT_KEY, "PARENT_PIN"));
    assertThat(prepared.getToolContext())
        .doesNotContainKey(ModelOptions.RUN_CONNECTION_CONTEXT_KEY);
    assertThat(prepared.getModel()).isEqualTo("static-helper-model");
  }

  @ParameterizedTest
  @EnumSource(LlmApiType.class)
  void acceptedRunOptionsCannotBeRelabeledAsAnIndependentFeatureCall(LlmApiType api) {
    var accepted =
        options(api)
            .mutate()
            .toolContext(Map.of(ModelOptions.RUN_CONNECTION_CONTEXT_KEY, "ACCEPTED_PIN"))
            .build();
    assertThatThrownBy(() -> ModelOptions.singleRequest(accepted))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Run connection");
  }

  private ToolCallingChatOptions options(LlmApiType api) {
    return api == LlmApiType.CHAT_COMPLETIONS
        ? OpenAiChatOptions.builder().model("static-helper-model").build()
        : OpenAiResponsesChatOptions.builder().model("static-helper-model").build();
  }
}
