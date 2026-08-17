package dev.mikoto2000.rei.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.bluesky.BlueskyPostTools;
import dev.mikoto2000.rei.briefing.BriefingTools;
import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.core.configuration.CoreProperties;
import dev.mikoto2000.rei.core.working.WorkingSetAdvisor;
import dev.mikoto2000.rei.core.configuration.SystemPromptService;
import dev.mikoto2000.rei.feed.FeedTools;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarTools;
import dev.mikoto2000.rei.reminder.ReminderTools;
import dev.mikoto2000.rei.search.SearchTools;
import dev.mikoto2000.rei.skills.AgentSkillAdvisor;
import dev.mikoto2000.rei.sound.SoundNotificationTools;
import dev.mikoto2000.rei.task.TaskTools;
import dev.mikoto2000.rei.urlfetch.UrlContentFetchTools;
import dev.mikoto2000.rei.websearch.WebSearchTools;

@Component
public class LlmChatClientProvider {

  private final LlmModelProvider modelProvider;
  private final CoreProperties coreProperties;
  private final SystemPromptService systemPromptService;
  private final ChatMemory chatMemory;
  private final ObjectProvider<Tools> tools;
  private final ObjectProvider<GoogleCalendarTools> googleCalendarTools;
  private final ObjectProvider<TaskTools> taskTools;
  private final ObjectProvider<BriefingTools> briefingTools;
  private final ObjectProvider<FeedTools> feedTools;
  private final ObjectProvider<ReminderTools> reminderTools;
  private final ObjectProvider<SearchTools> searchTools;
  private final ObjectProvider<WebSearchTools> webSearchTools;
  private final ObjectProvider<SoundNotificationTools> soundNotificationTools;
  private final ObjectProvider<BlueskyPostTools> blueskyPostTools;
  private final ObjectProvider<UrlContentFetchTools> urlContentFetchTools;
  private final ObjectProvider<AgentSkillAdvisor> agentSkillAdvisor;
  private final ObjectProvider<WorkingSetAdvisor> workingSetAdvisor;
  private final ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider;
  private final Map<String, ChatClient> cache = new ConcurrentHashMap<>();

  public LlmChatClientProvider(LlmModelProvider modelProvider, CoreProperties coreProperties,
      SystemPromptService systemPromptService, ChatMemory chatMemory,
      ObjectProvider<Tools> tools, ObjectProvider<GoogleCalendarTools> googleCalendarTools,
      ObjectProvider<TaskTools> taskTools, ObjectProvider<BriefingTools> briefingTools,
      ObjectProvider<FeedTools> feedTools, ObjectProvider<ReminderTools> reminderTools,
      ObjectProvider<SearchTools> searchTools, ObjectProvider<WebSearchTools> webSearchTools,
      ObjectProvider<SoundNotificationTools> soundNotificationTools, ObjectProvider<BlueskyPostTools> blueskyPostTools,
      ObjectProvider<UrlContentFetchTools> urlContentFetchTools, ObjectProvider<AgentSkillAdvisor> agentSkillAdvisor,
      ObjectProvider<WorkingSetAdvisor> workingSetAdvisor,
      ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider) {
    this.modelProvider = modelProvider;
    this.coreProperties = coreProperties;
    this.systemPromptService = systemPromptService;
    this.chatMemory = chatMemory;
    this.tools = tools;
    this.googleCalendarTools = googleCalendarTools;
    this.taskTools = taskTools;
    this.briefingTools = briefingTools;
    this.feedTools = feedTools;
    this.reminderTools = reminderTools;
    this.searchTools = searchTools;
    this.webSearchTools = webSearchTools;
    this.soundNotificationTools = soundNotificationTools;
    this.blueskyPostTools = blueskyPostTools;
    this.urlContentFetchTools = urlContentFetchTools;
    this.agentSkillAdvisor = agentSkillAdvisor;
    this.workingSetAdvisor = workingSetAdvisor;
    this.mcpToolCallbackProvider = mcpToolCallbackProvider;
  }

  public ChatClient chatClient(String feature) {
    return cache.computeIfAbsent(feature, this::createChatClient);
  }

  private ChatClient createChatClient(String feature) {
    List<Advisor> advisors = new ArrayList<>();
    advisors.add(PromptChatMemoryAdvisor.builder(chatMemory)
        .scheduler(BaseAdvisor.DEFAULT_SCHEDULER)
        .build());
    AgentSkillAdvisor skillAdvisor = agentSkillAdvisor.getIfAvailable();
    if (skillAdvisor != null) {
      advisors.add(skillAdvisor);
    }
    WorkingSetAdvisor workingSetAdvisorInstance = workingSetAdvisor.getIfAvailable();
    if (workingSetAdvisorInstance != null) {
      advisors.add(workingSetAdvisorInstance);
    }

    ChatClient.Builder builder = ChatClient.builder(modelProvider.chatModel(feature))
        .defaultSystem(systemPromptService.systemPrompt())
        .defaultOptions(modelProvider.chatOptions(feature, null))
        .defaultAdvisors(advisors);

    List<Object> toolObjects = new ArrayList<>();
    addIfAvailable(toolObjects, tools);
    addIfAvailable(toolObjects, googleCalendarTools);
    addIfAvailable(toolObjects, taskTools);
    addIfAvailable(toolObjects, briefingTools);
    addIfAvailable(toolObjects, feedTools);
    addIfAvailable(toolObjects, reminderTools);
    addIfAvailable(toolObjects, searchTools);
    addIfAvailable(toolObjects, webSearchTools);
    addIfAvailable(toolObjects, soundNotificationTools);
    addIfAvailable(toolObjects, blueskyPostTools);
    addIfAvailable(toolObjects, urlContentFetchTools);
    if (!toolObjects.isEmpty()) {
      builder.defaultTools(toolObjects.toArray());
    }

    ToolCallbackProvider toolCallbackProvider = mcpToolCallbackProvider.getIfAvailable();
    if (toolCallbackProvider != null) {
      builder.defaultToolCallbacks(toolCallbackProvider);
    }
    return builder.build();
  }

  private void addIfAvailable(List<Object> toolObjects, ObjectProvider<?> provider) {
    Object tool = provider.getIfAvailable();
    if (tool != null) {
      toolObjects.add(tool);
    }
  }
}
