package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Protocol selection must never silently use the legacy transport. */
class LlmProtocolSelectionTest {
  @Test
  void cachedModelAndRequestOptionsUseTheSameConfigurationSnapshot() {
    var properties = new LlmProperties();
    var server = new LlmProperties.Server();
    server.setBaseUrl("https://first.invalid");
    server.setModel("first-model");
    server.setMaxOutputTokens(1024);
    properties.setFeature("chat", server);
    var provider = new LlmModelProvider(Mockito.mock(ChatModel.class), properties);
    var model = provider.chatModel("chat");

    server.setBaseUrl("https://second.invalid");
    server.setModel("second-model");
    server.setMaxOutputTokens(2048);
    properties.setFeature("chat", server);

    assertThat(provider.chatOptions("chat", null).getModel()).isEqualTo("first-model");
    assertThat(provider.chatOptions("chat", null).getMaxTokens()).isEqualTo(1024);
    assertThat(provider.chatModel("chat")).isSameAs(model);
  }

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
