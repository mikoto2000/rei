package dev.mikoto2000.rei.core.configuration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.mikoto2000.rei.briefing.BriefingTools;
import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarProperties;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarTools;
import dev.mikoto2000.rei.reminder.ReminderTools;
import dev.mikoto2000.rei.task.TaskTools;
import dev.mikoto2000.rei.vectordocument.VectorDocumentTools;
import dev.mikoto2000.rei.websearch.WebSearchProperties;
import dev.mikoto2000.rei.websearch.WebSearchTools;
import lombok.RequiredArgsConstructor;

@Configuration
@EnableConfigurationProperties({GoogleCalendarProperties.class, WebSearchProperties.class})
@RequiredArgsConstructor
public class AiConfiguration {

  @Value("${rei.core.system-prompt}")
  private String systemPrompt;

  private final ChatModel chatModel;
  private final ChatMemory chatMemory;
  private final Tools tools;
  private final VectorStore vectorStore;
  private final GoogleCalendarTools googleCalendarTools;
  private final TaskTools taskTools;
  private final BriefingTools briefingTools;
  private final ReminderTools reminderTools;
  private final WebSearchTools webSearchTools;
  private final VectorDocumentTools vectorDocumentTools;

  @Bean
  public ChatClient chatClient() {
    return ChatClient.builder(chatModel)
        .defaultSystem(systemPrompt)
        .defaultAdvisors(
            MessageChatMemoryAdvisor.builder(chatMemory)
                .scheduler(BaseAdvisor.DEFAULT_SCHEDULER)
                .build(),
            QuestionAnswerAdvisor.builder(vectorStore).build())
        .defaultTools(tools, googleCalendarTools, taskTools, briefingTools, reminderTools, webSearchTools,
            vectorDocumentTools)
        .build();
  }
}
