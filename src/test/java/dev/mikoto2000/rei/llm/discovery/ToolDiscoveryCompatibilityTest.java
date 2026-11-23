package dev.mikoto2000.rei.llm.discovery;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
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
  void explicitEndpointCapabilityIsIndependentOfTheDiscoveryWindow() {
    var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
        java.util.Map.of("rei.llm.max-output-tokens", "8192", "rei.llm.max-tools", "256"));
    var properties = new org.springframework.boot.context.properties.bind.Binder(source)
        .bindOrCreate("rei.llm", dev.mikoto2000.rei.llm.LlmProperties.class);
    var options = new dev.mikoto2000.rei.llm.LlmModelProvider(null, properties)
        .chatOptions("chat", "fixture-model");
    assertThat(options.getToolContext()).containsEntry("rei.llm.max-tools", 256);
    var request = new Prompt(new UserMessage("fixture"),
        options.mutate().toolCallbacks(callbacks(129)).build());
    assertThatCode(() -> discovery(false).validateOutbound(request)).doesNotThrowAnyException();
    assertThatThrownBy(() -> discovery(true).validateOutbound(request))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("128");
  }

  @Test
  void unspecifiedEndpointCapabilityKeepsTheExistingConservativeLimit() {
    assertThatThrownBy(() -> discovery(false).validateOutbound(prompt(callbacks(129))))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("128");
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
