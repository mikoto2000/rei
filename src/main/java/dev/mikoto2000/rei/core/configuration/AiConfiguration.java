package dev.mikoto2000.rei.core.configuration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

import dev.mikoto2000.rei.bluesky.BlueskyPostTools;
import dev.mikoto2000.rei.bluesky.BlueskyProperties;
import dev.mikoto2000.rei.briefing.BriefingTools;
import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.core.filesummary.FileSummaryAdvisor;
import dev.mikoto2000.rei.core.recentchanges.RecentChangesAdvisor;
import dev.mikoto2000.rei.core.taskstate.TaskStateAdvisor;
import dev.mikoto2000.rei.core.taskstate.TaskStateTools;
import dev.mikoto2000.rei.core.working.WorkingSetAdvisor;
import dev.mikoto2000.rei.feed.FeedProperties;
import dev.mikoto2000.rei.feed.FeedTools;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarProperties;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarTools;
import dev.mikoto2000.rei.image.ImageProperties;
import dev.mikoto2000.rei.interest.InterestProperties;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.reminder.ReminderTools;
import dev.mikoto2000.rei.search.SearchTools;
import dev.mikoto2000.rei.skills.AgentSkillAdvisor;
import dev.mikoto2000.rei.skills.AgentSkillsProperties;
import dev.mikoto2000.rei.sound.SoundNotificationProperties;
import dev.mikoto2000.rei.sound.SoundNotificationTools;
import dev.mikoto2000.rei.task.TaskTools;
import dev.mikoto2000.rei.temporal.ClockTools;
import dev.mikoto2000.rei.temporal.RuntimeContextAdvisor;
import dev.mikoto2000.rei.temporal.SchedulerTools;
import dev.mikoto2000.rei.urlfetch.UrlContentFetchTools;
import dev.mikoto2000.rei.vectordocument.VectorDocumentProperties;
import dev.mikoto2000.rei.websearch.WebSearchProperties;
import dev.mikoto2000.rei.websearch.WebSearchTools;
import lombok.RequiredArgsConstructor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CoreProperties.class, GoogleCalendarProperties.class, WebSearchProperties.class, VectorDocumentProperties.class, SqliteVecProperties.class, InterestProperties.class, FeedProperties.class, SoundNotificationProperties.class, BlueskyProperties.class, AgentSkillsProperties.class, LlmProperties.class, ImageProperties.class})
@RequiredArgsConstructor
public class AiConfiguration {

  private final CoreProperties coreProperties;
  private final SystemPromptService systemPromptService;
  private final ChatModel chatModel;
  private final ChatMemory chatMemory;
  private final Tools tools;
  private final GoogleCalendarTools googleCalendarTools;
  private final TaskTools taskTools;
  private final BriefingTools briefingTools;
  private final FeedTools feedTools;
  private final ReminderTools reminderTools;
  private final SearchTools searchTools;
  private final WebSearchTools webSearchTools;
  private final SoundNotificationTools soundNotificationTools;
  private final BlueskyPostTools blueskyPostTools;
  private final UrlContentFetchTools urlContentFetchTools;
  private final ClockTools clockTools;
  private final SchedulerTools schedulerTools;
  private final RuntimeContextAdvisor runtimeContextAdvisor;
  private final WorkingSetAdvisor workingSetAdvisor;
  private final TaskStateAdvisor taskStateAdvisor;
  private final RecentChangesAdvisor recentChangesAdvisor;
  private final FileSummaryAdvisor fileSummaryAdvisor;
  private final TaskStateTools taskStateTools;
  private final LlmProperties llmProperties;
  private final ObjectProvider<AgentSkillAdvisor> agentSkillAdvisor;
  private final ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider;

  @Bean
  public ChatClient chatClient() {
    List<Advisor> advisors = new ArrayList<>();
    advisors.add(PromptChatMemoryAdvisor.builder(chatMemory)
        .scheduler(BaseAdvisor.DEFAULT_SCHEDULER)
        .build());
    advisors.add(runtimeContextAdvisor);
    advisors.add(workingSetAdvisor);
    advisors.add(taskStateAdvisor);
    advisors.add(recentChangesAdvisor);
    advisors.add(fileSummaryAdvisor);
    AgentSkillAdvisor skillAdvisor = agentSkillAdvisor.getIfAvailable();
    if (skillAdvisor != null) {
      advisors.add(skillAdvisor);
    }

    ChatClient.Builder builder = ChatClient.builder(chatModel)
        .defaultSystem(systemPromptService.systemPrompt())
        .defaultOptions(OpenAiChatOptions.builder()
            .maxTokens(llmProperties.getMaxOutputTokens())
            .build())
        .defaultAdvisors(advisors)
        .defaultTools(tools, googleCalendarTools, taskTools, briefingTools, feedTools, reminderTools, searchTools, webSearchTools,
            soundNotificationTools, blueskyPostTools, urlContentFetchTools, clockTools, schedulerTools, taskStateTools);

    ToolCallbackProvider toolCallbackProvider = mcpToolCallbackProvider.getIfAvailable();
    if (toolCallbackProvider != null) {
      builder.defaultToolCallbacks(toolCallbackProvider);
    }

    return builder.build();
  }
}
