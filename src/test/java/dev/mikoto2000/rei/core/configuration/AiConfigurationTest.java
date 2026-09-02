package dev.mikoto2000.rei.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.ObjectProvider;

import dev.mikoto2000.rei.bluesky.BlueskyPostTools;
import dev.mikoto2000.rei.briefing.BriefingTools;
import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.core.actionplan.ActionPlanAdvisor;
import dev.mikoto2000.rei.core.checkpoint.CheckpointAdvisor;
import dev.mikoto2000.rei.core.filesummary.FileSummaryAdvisor;
import dev.mikoto2000.rei.core.recentchanges.RecentChangesAdvisor;
import dev.mikoto2000.rei.core.relatedgraph.RelatedFileGraphAdvisor;
import dev.mikoto2000.rei.core.stagnation.StagnationAdvisor;
import dev.mikoto2000.rei.core.taskstate.TaskStateAdvisor;
import dev.mikoto2000.rei.core.taskstate.TaskStateTools;
import dev.mikoto2000.rei.core.working.WorkingSetAdvisor;
import dev.mikoto2000.rei.conversation.ConversationHistoryTools;
import dev.mikoto2000.rei.event.ToolEventCallbackProvider;
import dev.mikoto2000.rei.feed.FeedTools;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarProperties;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarTools;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.reminder.ReminderTools;
import dev.mikoto2000.rei.search.SearchTools;
import dev.mikoto2000.rei.skills.AgentSkillAdvisor;
import dev.mikoto2000.rei.sound.SoundNotificationTools;
import dev.mikoto2000.rei.task.TaskTools;
import dev.mikoto2000.rei.text.TextTools;
import dev.mikoto2000.rei.temporal.ClockTools;
import dev.mikoto2000.rei.temporal.RuntimeContextAdvisor;
import dev.mikoto2000.rei.temporal.SchedulerTools;
import dev.mikoto2000.rei.urlfetch.UrlContentFetchTools;
import dev.mikoto2000.rei.websearch.WebSearchProperties;
import dev.mikoto2000.rei.websearch.WebSearchTools;

class AiConfigurationTest {

  @Test
  void chatClientIncludesMcpToolCallbackProviderWhenAvailable() throws Exception {
    ToolEventCallbackProvider mcpToolCallbackProvider = Mockito.mock(ToolEventCallbackProvider.class);
    ObjectProvider<ToolEventCallbackProvider> provider = mockProviderReturning(mcpToolCallbackProvider);

    AiConfiguration configuration = new AiConfiguration(
        new CoreProperties("system prompt", 100),
        systemPromptService(),
        Mockito.mock(ChatModel.class),
        Mockito.mock(ChatMemory.class),
        new Tools(),
        Mockito.mock(GoogleCalendarTools.class),
        Mockito.mock(TaskTools.class),
        Mockito.mock(BriefingTools.class),
        Mockito.mock(FeedTools.class),
        Mockito.mock(ReminderTools.class),
        Mockito.mock(SearchTools.class),
        Mockito.mock(WebSearchTools.class),
        Mockito.mock(SoundNotificationTools.class),
        Mockito.mock(BlueskyPostTools.class),
        Mockito.mock(UrlContentFetchTools.class),
        Mockito.mock(TextTools.class),
        Mockito.mock(ClockTools.class),
        Mockito.mock(SchedulerTools.class),
        Mockito.mock(ConversationHistoryTools.class),
        Mockito.mock(RuntimeContextAdvisor.class),
        Mockito.mock(WorkingSetAdvisor.class),
        Mockito.mock(TaskStateAdvisor.class),
        Mockito.mock(RecentChangesAdvisor.class),
        Mockito.mock(FileSummaryAdvisor.class),
        Mockito.mock(RelatedFileGraphAdvisor.class),
        Mockito.mock(CheckpointAdvisor.class),
        Mockito.mock(ActionPlanAdvisor.class),
        Mockito.mock(StagnationAdvisor.class),
        Mockito.mock(TaskStateTools.class),
        new LlmProperties(),
        mockProviderReturning(null),
        provider);

    ChatClient chatClient = configuration.chatClient();

    List<?> toolCallbackProviders = getDefaultToolCallbackProviders(chatClient);
    assertEquals(1, toolCallbackProviders.size());
    assertSame(mcpToolCallbackProvider, toolCallbackProviders.getFirst());
  }

  @Test
  void chatClientOmitsMcpToolCallbackProviderWhenUnavailable() throws Exception {
    ObjectProvider<ToolEventCallbackProvider> provider = mockProviderReturning(null);

    AiConfiguration configuration = new AiConfiguration(
        new CoreProperties("system prompt", 100),
        systemPromptService(),
        Mockito.mock(ChatModel.class),
        Mockito.mock(ChatMemory.class),
        new Tools(),
        Mockito.mock(GoogleCalendarTools.class),
        Mockito.mock(TaskTools.class),
        Mockito.mock(BriefingTools.class),
        Mockito.mock(FeedTools.class),
        Mockito.mock(ReminderTools.class),
        Mockito.mock(SearchTools.class),
        Mockito.mock(WebSearchTools.class),
        Mockito.mock(SoundNotificationTools.class),
        Mockito.mock(BlueskyPostTools.class),
        Mockito.mock(UrlContentFetchTools.class),
        Mockito.mock(TextTools.class),
        Mockito.mock(ClockTools.class),
        Mockito.mock(SchedulerTools.class),
        Mockito.mock(ConversationHistoryTools.class),
        Mockito.mock(RuntimeContextAdvisor.class),
        Mockito.mock(WorkingSetAdvisor.class),
        Mockito.mock(TaskStateAdvisor.class),
        Mockito.mock(RecentChangesAdvisor.class),
        Mockito.mock(FileSummaryAdvisor.class),
        Mockito.mock(RelatedFileGraphAdvisor.class),
        Mockito.mock(CheckpointAdvisor.class),
        Mockito.mock(ActionPlanAdvisor.class),
        Mockito.mock(StagnationAdvisor.class),
        Mockito.mock(TaskStateTools.class),
        new LlmProperties(),
        mockProviderReturning(null),
        provider);

    ChatClient chatClient = configuration.chatClient();

    List<?> toolCallbackProviders = getDefaultToolCallbackProviders(chatClient);
    assertEquals(0, toolCallbackProviders.size());
  }

  @Test
  void chatClientIncludesAgentSkillAdvisorWhenAvailable() throws Exception {
    AgentSkillAdvisor agentSkillAdvisor = Mockito.mock(AgentSkillAdvisor.class);

    AiConfiguration configuration = new AiConfiguration(
        new CoreProperties("system prompt", 100),
        systemPromptService(),
        Mockito.mock(ChatModel.class),
        Mockito.mock(ChatMemory.class),
        new Tools(),
        Mockito.mock(GoogleCalendarTools.class),
        Mockito.mock(TaskTools.class),
        Mockito.mock(BriefingTools.class),
        Mockito.mock(FeedTools.class),
        Mockito.mock(ReminderTools.class),
        Mockito.mock(SearchTools.class),
        Mockito.mock(WebSearchTools.class),
        Mockito.mock(SoundNotificationTools.class),
        Mockito.mock(BlueskyPostTools.class),
        Mockito.mock(UrlContentFetchTools.class),
        Mockito.mock(TextTools.class),
        Mockito.mock(ClockTools.class),
        Mockito.mock(SchedulerTools.class),
        Mockito.mock(ConversationHistoryTools.class),
        Mockito.mock(RuntimeContextAdvisor.class),
        Mockito.mock(WorkingSetAdvisor.class),
        Mockito.mock(TaskStateAdvisor.class),
        Mockito.mock(RecentChangesAdvisor.class),
        Mockito.mock(FileSummaryAdvisor.class),
        Mockito.mock(RelatedFileGraphAdvisor.class),
        Mockito.mock(CheckpointAdvisor.class),
        Mockito.mock(ActionPlanAdvisor.class),
        Mockito.mock(StagnationAdvisor.class),
        Mockito.mock(TaskStateTools.class),
        new LlmProperties(),
        mockProviderReturning(agentSkillAdvisor),
        mockProviderReturning(null));

    ChatClient chatClient = configuration.chatClient();

    List<?> advisors = getDefaultAdvisors(chatClient);
    assertSame(agentSkillAdvisor, advisors.getLast());
  }

  @Test
  void chatClientUsesConfiguredMaxOutputTokensAsDefaultOption() throws Exception {
    LlmProperties llmProperties = new LlmProperties();
    llmProperties.setMaxOutputTokens(4096);

    AiConfiguration configuration = new AiConfiguration(
        new CoreProperties("system prompt", 100),
        systemPromptService(),
        Mockito.mock(ChatModel.class),
        Mockito.mock(ChatMemory.class),
        new Tools(),
        Mockito.mock(GoogleCalendarTools.class),
        Mockito.mock(TaskTools.class),
        Mockito.mock(BriefingTools.class),
        Mockito.mock(FeedTools.class),
        Mockito.mock(ReminderTools.class),
        Mockito.mock(SearchTools.class),
        Mockito.mock(WebSearchTools.class),
        Mockito.mock(SoundNotificationTools.class),
        Mockito.mock(BlueskyPostTools.class),
        Mockito.mock(UrlContentFetchTools.class),
        Mockito.mock(TextTools.class),
        Mockito.mock(ClockTools.class),
        Mockito.mock(SchedulerTools.class),
        Mockito.mock(ConversationHistoryTools.class),
        Mockito.mock(RuntimeContextAdvisor.class),
        Mockito.mock(WorkingSetAdvisor.class),
        Mockito.mock(TaskStateAdvisor.class),
        Mockito.mock(RecentChangesAdvisor.class),
        Mockito.mock(FileSummaryAdvisor.class),
        Mockito.mock(RelatedFileGraphAdvisor.class),
        Mockito.mock(CheckpointAdvisor.class),
        Mockito.mock(ActionPlanAdvisor.class),
        Mockito.mock(StagnationAdvisor.class),
        Mockito.mock(TaskStateTools.class),
        llmProperties,
        mockProviderReturning(null),
        mockProviderReturning(null));

    ChatOptions options = getDefaultChatOptions(configuration.chatClient());

    assertEquals(4096, options.getMaxTokens());
  }

  @SuppressWarnings("unchecked")
  private List<?> getDefaultToolCallbackProviders(ChatClient chatClient) throws Exception {
    Object defaultRequest = getDefaultChatClientRequest(chatClient);

    Field toolCallbackProvidersField = defaultRequest.getClass().getDeclaredField("toolCallbackProviders");
    toolCallbackProvidersField.setAccessible(true);
    return (List<?>) toolCallbackProvidersField.get(defaultRequest);
  }

  @SuppressWarnings("unchecked")
  private List<?> getDefaultAdvisors(ChatClient chatClient) throws Exception {
    Object defaultRequest = getDefaultChatClientRequest(chatClient);

    Field advisorsField = defaultRequest.getClass().getDeclaredField("advisors");
    advisorsField.setAccessible(true);
    return (List<Advisor>) advisorsField.get(defaultRequest);
  }

  private ChatOptions getDefaultChatOptions(ChatClient chatClient) throws Exception {
    Object defaultRequest = getDefaultChatClientRequest(chatClient);
    Field chatOptionsField = defaultRequest.getClass().getDeclaredField("chatOptions");
    chatOptionsField.setAccessible(true);
    return (ChatOptions) chatOptionsField.get(defaultRequest);
  }

  private Object getDefaultChatClientRequest(ChatClient chatClient) throws Exception {
    Field defaultRequestField = chatClient.getClass().getDeclaredField("defaultChatClientRequest");
    defaultRequestField.setAccessible(true);
    return defaultRequestField.get(chatClient);
  }

  private <T> ObjectProvider<T> mockProviderReturning(T value) {
    @SuppressWarnings("unchecked")
    ObjectProvider<T> provider = Mockito.mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }

  private SystemPromptService systemPromptService() {
    SystemPromptService service = Mockito.mock(SystemPromptService.class);
    when(service.systemPrompt()).thenReturn("system prompt");
    return service;
  }
}
