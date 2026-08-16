package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;

class LlmModelProviderTest {

  @Test
  void returnsDefaultChatModelWhenFeatureServerIsNotConfigured() {
    ChatModel defaultModel = Mockito.mock(ChatModel.class);
    LlmModelProvider provider = new LlmModelProvider(defaultModel, new LlmProperties());

    assertThat(provider.chatModel(LlmFeature.CHAT)).isSameAs(defaultModel);
    assertThat(provider.model(LlmFeature.CHAT, "default-model")).isEqualTo("default-model");
    assertThat(provider.chatOptions(LlmFeature.CHAT, "default-model").getMaxTokens()).isEqualTo(8192);
  }

  @Test
  void createsOpenAiCompatibleChatModelWhenFeatureServerIsConfigured() {
    ChatModel defaultModel = Mockito.mock(ChatModel.class);
    LlmProperties properties = new LlmProperties();
    LlmProperties.Server server = new LlmProperties.Server();
    server.setBaseUrl("http://feature.example.test");
    server.setApiKey("feature-key");
    server.setModel("feature-model");
    properties.getFeatures().put(LlmFeature.SEARCH, server);

    LlmModelProvider provider = new LlmModelProvider(defaultModel, properties);

    assertThat(provider.chatModel(LlmFeature.SEARCH)).isInstanceOf(FallbackChatModel.class);
    assertThat(provider.chatModel(LlmFeature.SEARCH)).isSameAs(provider.chatModel(LlmFeature.SEARCH));
    assertThat(provider.model(LlmFeature.SEARCH, "default-model")).isEqualTo("feature-model");
    assertThat(provider.chatOptions(LlmFeature.SEARCH, "default-model").getMaxTokens()).isEqualTo(8192);
  }

  @Test
  void appliesConfiguredMaxOutputTokensToChatOptions() {
    ChatModel defaultModel = Mockito.mock(ChatModel.class);
    LlmProperties properties = new LlmProperties();
    properties.setMaxOutputTokens(2048);
    LlmModelProvider provider = new LlmModelProvider(defaultModel, properties);

    assertThat(provider.chatOptions(LlmFeature.CHAT, "default-model").getMaxTokens()).isEqualTo(2048);
  }

  @Test
  void fallsBackToDefaultMaxOutputTokensWhenConfiguredValueIsInvalid() {
    ChatModel defaultModel = Mockito.mock(ChatModel.class);
    LlmProperties properties = new LlmProperties();
    properties.setMaxOutputTokens(0);
    LlmModelProvider provider = new LlmModelProvider(defaultModel, properties);

    assertThat(provider.chatOptions(LlmFeature.CHAT, "default-model").getMaxTokens()).isEqualTo(8192);
  }
}
