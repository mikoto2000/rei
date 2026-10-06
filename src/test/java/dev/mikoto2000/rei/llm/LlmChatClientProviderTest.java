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
  void memoryProcessesOnlySuppliedEvidenceWithoutToolsOrAdvisors() {
    var prompts = new java.util.ArrayList<org.springframework.ai.chat.prompt.Prompt>();
    ChatModel model = new ChatModel() {
      @Override public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        return org.springframework.ai.openai.OpenAiChatOptions.builder().build();
      }
      @Override public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
        prompts.add(prompt);
        return new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
            new org.springframework.ai.chat.messages.AssistantMessage("[]"))));
      }
    };
    var models = mock(LlmModelProvider.class);
    when(models.memoryChatModel()).thenReturn(model);
    var originalOptions = org.springframework.ai.openai.OpenAiChatOptions.builder()
        .model("memory-model").temperature(0.2).maxCompletionTokens(321)
        .toolChoice("auto").build();
    when(models.chatOptions(LlmFeature.MEMORY, null)).thenReturn(originalOptions);
    var memory = mock(ChatMemory.class);
    var system = mock(SystemPromptService.class);
    var provider = new LlmChatClientProvider(models, new CoreProperties("system", 100), system, memory,
        optional(mock(dev.mikoto2000.rei.core.Tools.class)), optional(null), optional(null), optional(null),
        optional(null), optional(null), optional(null), optional(null),
        optional(null), optional(null), optional(null), optional(null),
        optional(mock(dev.mikoto2000.rei.temporal.RuntimeContextAdvisor.class)), optional(null),
        optional(mock(dev.mikoto2000.rei.core.working.WorkingSetAdvisor.class)),
        optional(mock(dev.mikoto2000.rei.core.taskstate.TaskStateAdvisor.class)),
        optional(null), optional(mock(dev.mikoto2000.rei.event.ToolEventCallbackProvider.class)), null, null);

    assertThat(provider.chatClient(LlmFeature.MEMORY).prompt("supplied evidence").call().content()).isEqualTo("[]");
    assertThat(prompts).hasSize(1);
    assertThat(prompts.getFirst().getInstructions()).hasSize(1);
    assertThat(prompts.getFirst().getInstructions().getFirst().getText()).isEqualTo("supplied evidence");
    var options = (org.springframework.ai.model.tool.ToolCallingChatOptions) prompts.getFirst().getOptions();
    assertThat(((org.springframework.ai.openai.OpenAiChatOptions) options).getToolChoice()).isEqualTo("none");
    assertThat(options.getToolCallbacks()).isEmpty();
    assertThat(options.getModel()).isEqualTo("memory-model");
    assertThat(options.getTemperature()).isEqualTo(0.2);
    assertThat(((org.springframework.ai.openai.OpenAiChatOptions) options).getMaxCompletionTokens()).isEqualTo(321);
    assertThat(originalOptions.getToolChoice()).isEqualTo("auto");
    org.mockito.Mockito.verifyNoInteractions(memory, system);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"chat", "search", "feed-summary"})
  void requestsWithoutAnApplicationRunRetainToolCalling(String feature) {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var toolCalls = new java.util.concurrent.atomic.AtomicInteger();
    ChatModel model = new ChatModel() {
      @Override public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        return org.springframework.ai.openai.OpenAiChatOptions.builder().model("local").build();
      }
      @Override public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
      if (calls.incrementAndGet() > 1) return new org.springframework.ai.chat.model.ChatResponse(List.of(
          new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("done"))));
      return new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
          org.springframework.ai.chat.messages.AssistantMessage.builder().content("").toolCalls(List.of(
              new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("call-1", "function", "test", "{}"))).build())));
      }
    };
    var models = mock(LlmModelProvider.class);
    when(models.chatModel(feature)).thenReturn(model);
    when(models.chatOptions(feature, null)).thenReturn(org.springframework.ai.openai.OpenAiChatOptions.builder()
        .model("local").build());
    var system = mock(SystemPromptService.class); when(system.systemPrompt()).thenReturn("system");
    var provider = new LlmChatClientProvider(models, new CoreProperties("system", 100), system,
        org.springframework.ai.chat.memory.MessageWindowChatMemory.builder().build(),
        optional(null), optional(null), optional(null), optional(null),
        optional(null), optional(null), optional(null), optional(null),
        optional(null), optional(null), optional(null), optional(null),
        optional(null), optional(null), optional(null), optional(null),
        optional(null), optional(null), null, null);
    var callback = new org.springframework.ai.tool.ToolCallback() {
      @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
        return org.springframework.ai.tool.definition.ToolDefinition.builder().name("test").description("test")
            .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
      }
      @Override public String call(String input) { toolCalls.incrementAndGet(); return "ok"; }
    };

    var response = provider.chatClient(feature).prompt().user("test").toolCallbacks(callback).call().chatResponse();

    assertThat(response.hasToolCalls()).isFalse();
    assertThat(calls).hasValue(2);
    assertThat(toolCalls).hasValue(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void persistedSessionHistoryIsAvailableRegardlessOfCompression(boolean compression,
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
    var turns = new dev.mikoto2000.rei.conversation.ConversationTurnStore(directory);
    var context = new dev.mikoto2000.rei.core.chat.AgentRunContext("saved-run", "chat:saved", directory);
    turns.startOrdered(context, "persisted-session-question", java.time.Instant.now());
    turns.finish(context, dev.mikoto2000.rei.conversation.ConversationTurnStore.Status.COMPLETED, "persisted-session-answer");
    var prompts = new java.util.ArrayList<org.springframework.ai.chat.prompt.Prompt>();
    ChatModel model = prompt -> {
      prompts.add(prompt);
      return new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
          new org.springframework.ai.chat.messages.AssistantMessage("response"))));
    };
    var models = mock(LlmModelProvider.class);
    when(models.chatModel(LlmFeature.CHAT)).thenReturn(model);
    when(models.chatOptions(LlmFeature.CHAT, null)).thenReturn(org.springframework.ai.openai.OpenAiChatOptions.builder().build());
    var system = mock(SystemPromptService.class); when(system.systemPrompt()).thenReturn("system");
    var memory = org.springframework.ai.chat.memory.MessageWindowChatMemory.builder().build();
    var provider = new LlmChatClientProvider(models, new CoreProperties("system", 100), system, memory,
        optional(null), optional(null), optional(null), optional(null),
        optional(null), optional(null), optional(null), optional(null),
        optional(null), optional(null), optional(null), optional(null),
        optional(null), optional(null), optional(null), optional(null),
        optional(null), optional(null), null, null);
    var properties = new dev.mikoto2000.rei.core.contextbudget.ContextCompressionProperties();
    properties.setEnabled(compression);
    var history = new dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor(
        new dev.mikoto2000.rei.conversation.ConversationTurnStore(directory), memory,
        new dev.mikoto2000.rei.core.contextbudget.ConversationSummaryRepository(directory));
    provider.setContextCompression(null, history, null, properties);
    provider.chatClient(LlmFeature.CHAT).prompt().user("continue")
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "chat:saved")).call().content();
    assertThat(prompts.getLast().toString()).contains("persisted-session-question", "persisted-session-answer");
  }

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

  @Test
  void blueskyReplyDoesNotExposeTools() throws Exception {
    var models = mock(LlmModelProvider.class);
    when(models.chatModel(LlmFeature.BLUESKY_REPLY)).thenReturn(mock(ChatModel.class));
    when(models.chatOptions(LlmFeature.BLUESKY_REPLY, null))
        .thenReturn(org.springframework.ai.openai.OpenAiChatOptions.builder().build());
    var systemPrompt = mock(SystemPromptService.class);
    when(systemPrompt.systemPrompt()).thenReturn("system prompt");
    var provider = new LlmChatClientProvider(models, new CoreProperties("system prompt", 100),
        systemPrompt, mock(ChatMemory.class),
        optional(null), optional(null), optional(null), optional(null),
        optional(null), optional(null), optional(null), optional(null),
        optional(null), optional(mock(dev.mikoto2000.rei.bluesky.BlueskyPostTools.class)), optional(null), optional(null),
        optional(null), optional(null), optional(null), optional(null),
        optional(null), optional(mock(dev.mikoto2000.rei.event.ToolEventCallbackProvider.class)), null, null);
    provider.setSummaryTools(optional(mock(SummaryTools.class)));

    var client = provider.chatClient(LlmFeature.BLUESKY_REPLY);
    var requestField = client.getClass().getDeclaredField("defaultChatClientRequest");
    requestField.setAccessible(true);
    var request = requestField.get(client);
    var callbacksField = request.getClass().getDeclaredField("toolCallbackProviders");
    callbacksField.setAccessible(true);
    var callbacks = ((List<?>) callbacksField.get(request)).stream()
        .map(ToolCallbackProvider.class::cast)
        .flatMap(callbacksProvider -> Arrays.stream(callbacksProvider.getToolCallbacks()));
    assertThat(callbacks.map(callback -> callback.getToolDefinition().name()))
        .isEmpty();
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
