package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;

class LlmModelProviderTest {

  @Test
  void computerUseOutputBudgetDoesNotChangeChatBudget() {
    LlmProperties properties = new LlmProperties();
    properties.setMaxOutputTokens(16384);
    LlmProperties.Server server = new LlmProperties.Server();
    server.setMaxOutputTokens(512);
    properties.getFeatures().put(LlmFeature.COMPUTER_USE, server);
    var provider = new LlmModelProvider(Mockito.mock(ChatModel.class), properties);
    assertThat(provider.chatOptions(LlmFeature.COMPUTER_USE, "vision").getMaxTokens()).isEqualTo(512);
    assertThat(provider.chatOptions(LlmFeature.CHAT, "chat").getMaxTokens()).isEqualTo(16384);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> server.setMaxOutputTokens(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void computerUseCustomServerDoesNotWrapModelWithFallback() {
    ChatModel defaultModel = Mockito.mock(ChatModel.class);
    LlmProperties properties = new LlmProperties();
    LlmProperties.Server server = new LlmProperties.Server();
    server.setBaseUrl("http://vision.example.test");
    server.setModel("vision-model");
    properties.getFeatures().put(LlmFeature.COMPUTER_USE, server);

    var model = new LlmModelProvider(defaultModel, properties).computerUseChatModel();

    assertThat(model).isInstanceOf(org.springframework.ai.openai.OpenAiChatModel.class);
    assertThat(model).isNotSameAs(defaultModel);
    Mockito.verify(defaultModel, Mockito.never()).call(Mockito.any(org.springframework.ai.chat.prompt.Prompt.class));
  }

  @Test
  void featureConnectionsUseOsNameResolutionForLocalHosts() {
    assertThat(LlmModelProvider.featureHttpClient().configuration().resolver())
        .isSameAs(io.netty.resolver.DefaultAddressResolverGroup.INSTANCE);
  }

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
