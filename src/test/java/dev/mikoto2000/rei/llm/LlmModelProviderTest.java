package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;

class LlmModelProviderTest {

  @Test
  void returnsDefaultChatModelWhenFeatureServerIsNotConfigured() {
    ChatModel defaultModel = Mockito.mock(ChatModel.class);
    LlmModelProvider provider = new LlmModelProvider(defaultModel, new LlmProperties());

    assertThat(provider.chatModel(LlmFeature.CHAT)).isSameAs(defaultModel);
    assertThat(provider.model(LlmFeature.CHAT, "default-model")).isEqualTo("default-model");
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

    assertThat(provider.chatModel(LlmFeature.SEARCH)).isInstanceOf(OpenAiChatModel.class);
    assertThat(provider.chatModel(LlmFeature.SEARCH)).isSameAs(provider.chatModel(LlmFeature.SEARCH));
    assertThat(provider.model(LlmFeature.SEARCH, "default-model")).isEqualTo("feature-model");
  }
}
