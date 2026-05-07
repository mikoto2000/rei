package dev.mikoto2000.rei.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

import dev.mikoto2000.rei.bluesky.BlueskyPostTools;
import dev.mikoto2000.rei.briefing.BriefingTools;
import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.feed.FeedTools;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarProperties;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarTools;
import dev.mikoto2000.rei.reminder.ReminderTools;
import dev.mikoto2000.rei.search.SearchTools;
import dev.mikoto2000.rei.sound.SoundNotificationTools;
import dev.mikoto2000.rei.task.TaskTools;
import dev.mikoto2000.rei.urlfetch.UrlContentFetchTools;
import dev.mikoto2000.rei.websearch.WebSearchProperties;
import dev.mikoto2000.rei.websearch.WebSearchTools;

class AiConfigurationTest {

  @Test
  void chatClientIncludesMcpToolCallbackProviderWhenAvailable() throws Exception {
    ToolCallbackProvider mcpToolCallbackProvider = Mockito.mock(ToolCallbackProvider.class);
    ObjectProvider<ToolCallbackProvider> provider = mockProviderReturning(mcpToolCallbackProvider);

    AiConfiguration configuration = new AiConfiguration(
        new CoreProperties("system prompt", 100),
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
        provider);

    ChatClient chatClient = configuration.chatClient();

    List<?> toolCallbackProviders = getDefaultToolCallbackProviders(chatClient);
    assertEquals(1, toolCallbackProviders.size());
    assertSame(mcpToolCallbackProvider, toolCallbackProviders.getFirst());
  }

  @Test
  void chatClientOmitsMcpToolCallbackProviderWhenUnavailable() throws Exception {
    ObjectProvider<ToolCallbackProvider> provider = mockProviderReturning(null);

    AiConfiguration configuration = new AiConfiguration(
        new CoreProperties("system prompt", 100),
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
        provider);

    ChatClient chatClient = configuration.chatClient();

    List<?> toolCallbackProviders = getDefaultToolCallbackProviders(chatClient);
    assertEquals(0, toolCallbackProviders.size());
  }

  @SuppressWarnings("unchecked")
  private List<?> getDefaultToolCallbackProviders(ChatClient chatClient) throws Exception {
    Field defaultRequestField = chatClient.getClass().getDeclaredField("defaultChatClientRequest");
    defaultRequestField.setAccessible(true);
    Object defaultRequest = defaultRequestField.get(chatClient);

    Field toolCallbackProvidersField = defaultRequest.getClass().getDeclaredField("toolCallbackProviders");
    toolCallbackProvidersField.setAccessible(true);
    return (List<?>) toolCallbackProvidersField.get(defaultRequest);
  }

  private ObjectProvider<ToolCallbackProvider> mockProviderReturning(ToolCallbackProvider toolCallbackProvider) {
    @SuppressWarnings("unchecked")
    ObjectProvider<ToolCallbackProvider> provider = Mockito.mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(toolCallbackProvider);
    return provider;
  }
}
