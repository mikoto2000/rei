package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Protocol selection must never silently use the legacy transport. */
class LlmProtocolSelectionTest {
  @Test
  void explicitResponsesIsUnavailableUntilDurableAgentIntegrationIsVerified() {
    var source = new MapConfigurationPropertySource(
        Map.of("rei.llm.features.chat.api", "responses"));
    var properties = new Binder(source).bindOrCreate("rei.llm", LlmProperties.class);
    var provider = new LlmModelProvider(Mockito.mock(ChatModel.class), properties);

    assertThatThrownBy(() -> provider.chatOptions("chat", "model"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Responses");
    assertThatThrownBy(() -> provider.chatModel("chat"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Responses");
  }
}
