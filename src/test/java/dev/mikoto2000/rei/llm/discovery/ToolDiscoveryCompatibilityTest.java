package dev.mikoto2000.rei.llm.discovery;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** A discovery visibility budget is not an inferred limit of every legacy endpoint. */
class ToolDiscoveryCompatibilityTest {
  @Test
  void disabledDiscoveryPreservesLegacyToolCountForAnUnspecifiedEndpointCapability() {
    assertThatCode(() -> discovery(false).validateOutbound(prompt(callbacks(129))))
        .doesNotThrowAnyException();
  }

  @Test
  void enabledDiscoveryStillEnforcesItsConfiguredVisibilityBudget() {
    assertThatThrownBy(() -> discovery(true).validateOutbound(prompt(callbacks(129))))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("128");
  }

  @Test
  void disabledDiscoveryStillRejectsDuplicateDefinitions() {
    var callback = callbacks(1).getFirst();
    assertThatThrownBy(() -> discovery(false).validateOutbound(prompt(List.of(callback, callback))))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate");
  }

  private ToolDiscovery discovery(boolean enabled) {
    var properties = new ToolDiscoveryProperties();
    properties.setEnabled(enabled);
    return new ToolDiscovery(properties,
        new DefaultListableBeanFactory().getBeanProvider(ToolDiscoveryStore.class));
  }

  private Prompt prompt(List<ToolCallback> callbacks) {
    return new Prompt(new UserMessage("fixture"),
        OpenAiChatOptions.builder().toolCallbacks(callbacks).build());
  }

  private List<ToolCallback> callbacks(int count) {
    return IntStream.range(0, count).mapToObj(index -> (ToolCallback)
        FunctionToolCallback.builder("fixture" + index, (String value) -> value)
            .description("fixture only").inputType(String.class).build()).toList();
  }
}
