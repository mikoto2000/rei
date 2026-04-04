package dev.mikoto2000.rei.core.configuration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

import dev.mikoto2000.rei.briefing.BriefingTools;
import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarProperties;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarTools;
import dev.mikoto2000.rei.reminder.ReminderTools;
import dev.mikoto2000.rei.search.SearchTools;
import dev.mikoto2000.rei.task.TaskTools;
import dev.mikoto2000.rei.vectordocument.VectorDocumentProperties;
import dev.mikoto2000.rei.websearch.WebSearchProperties;
import dev.mikoto2000.rei.websearch.WebSearchTools;
import lombok.RequiredArgsConstructor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CoreProperties.class, GoogleCalendarProperties.class, WebSearchProperties.class, VectorDocumentProperties.class, SqliteVecProperties.class})
@RequiredArgsConstructor
public class AiConfiguration {

  private final CoreProperties coreProperties;
  private final ChatModel chatModel;
  private final ChatMemory chatMemory;
  private final Tools tools;
  private final GoogleCalendarTools googleCalendarTools;
  private final TaskTools taskTools;
  private final BriefingTools briefingTools;
  private final ReminderTools reminderTools;
  private final SearchTools searchTools;
  private final WebSearchTools webSearchTools;
  private final ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider;

  @Bean
  public ChatClient chatClient() {
    ChatClient.Builder builder = ChatClient.builder(chatModel)
        .defaultSystem(coreProperties.systemPrompt())
        .defaultAdvisors(
            PromptChatMemoryAdvisor.builder(chatMemory)
                .scheduler(BaseAdvisor.DEFAULT_SCHEDULER)
                .build())
        .defaultTools(tools, googleCalendarTools, taskTools, briefingTools, reminderTools, searchTools, webSearchTools);

    ToolCallbackProvider toolCallbackProvider = mcpToolCallbackProvider.getIfAvailable();
    if (toolCallbackProvider != null) {
      builder.defaultToolCallbacks(toolCallbackProvider);
    }

    return builder.build();
  }
}
