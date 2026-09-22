package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

import dev.mikoto2000.rei.core.configuration.CoreProperties;
import dev.mikoto2000.rei.core.configuration.SystemPromptService;
import dev.mikoto2000.rei.summarize.SummaryTools;

class LlmChatClientProviderTest {

  @Test
  void chatClientExposesLastSummaryTool() throws Exception {
    var models = mock(LlmModelProvider.class);
    when(models.chatModel(LlmFeature.CHAT)).thenReturn(mock(ChatModel.class));
    when(models.chatOptions(LlmFeature.CHAT, null))
        .thenReturn(org.springframework.ai.openai.OpenAiChatOptions.builder().build());
    var systemPrompt = mock(SystemPromptService.class);
    when(systemPrompt.systemPrompt()).thenReturn("system prompt");
    var provider = new LlmChatClientProvider(models, new CoreProperties("system prompt", 100),
        systemPrompt, mock(ChatMemory.class),
        optional(null), optional(null), optional(null), optional(null),
        optional(null), optional(null), optional(null), optional(null),
        optional(null), optional(null), optional(null), optional(null),
        optional(null), optional(null), optional(null), optional(null),
        optional(null), optional(null), null, null);
    provider.setSummaryTools(optional(mock(SummaryTools.class)));

    var client = provider.chatClient(LlmFeature.CHAT);
    var requestField = client.getClass().getDeclaredField("defaultChatClientRequest");
    requestField.setAccessible(true);
    var request = requestField.get(client);
    var callbacksField = request.getClass().getDeclaredField("toolCallbackProviders");
    callbacksField.setAccessible(true);
    var callbacks = ((List<?>) callbacksField.get(request)).stream()
        .map(ToolCallbackProvider.class::cast)
        .flatMap(callbacksProvider -> Arrays.stream(callbacksProvider.getToolCallbacks()));
    assertThat(callbacks.map(callback -> callback.getToolDefinition().name()))
        .containsExactly("getLastSummary");
  }

  private static <T> ObjectProvider<T> optional(T value) {
    @SuppressWarnings("unchecked")
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }

  @Test
  void agentSkillsAreEnabledOnlyForChatFeature() {
    assertThat(LlmChatClientProvider.supportsAgentSkills(LlmFeature.CHAT)).isTrue();
    assertThat(LlmChatClientProvider.supportsAgentSkills(LlmFeature.SEARCH)).isFalse();
    assertThat(LlmChatClientProvider.supportsAgentSkills(LlmFeature.MEMORY)).isFalse();
    assertThat(LlmChatClientProvider.supportsAgentSkills(LlmFeature.BLUESKY_REPLY)).isFalse();
    assertThat(LlmChatClientProvider.supportsAgentSkills(LlmFeature.FEED_SUMMARY)).isFalse();
    assertThat(LlmChatClientProvider.supportsAgentSkills(LlmFeature.BRIEFING)).isFalse();
  }
}
