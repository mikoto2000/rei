package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.bluesky.BlueskyPostTools;
import dev.mikoto2000.rei.briefing.BriefingTools;
import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.core.configuration.CoreProperties;
import dev.mikoto2000.rei.core.configuration.SystemPromptService;
import dev.mikoto2000.rei.core.taskstate.TaskStateAdvisor;
import dev.mikoto2000.rei.core.taskstate.TaskStateTools;
import dev.mikoto2000.rei.core.working.WorkingSetAdvisor;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.event.ToolEventCallbackProvider;
import dev.mikoto2000.rei.feed.FeedTools;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarTools;
import dev.mikoto2000.rei.reminder.ReminderTools;
import dev.mikoto2000.rei.search.SearchTools;
import dev.mikoto2000.rei.skills.AgentSkillAdvisor;
import dev.mikoto2000.rei.sound.SoundNotificationTools;
import dev.mikoto2000.rei.summarize.SummaryTools;
import dev.mikoto2000.rei.task.TaskTools;
import dev.mikoto2000.rei.temporal.RuntimeContextAdvisor;
import dev.mikoto2000.rei.text.TextTools;
import dev.mikoto2000.rei.urlfetch.UrlContentFetchTools;
import dev.mikoto2000.rei.websearch.WebSearchTools;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 言語モデルモデルへ対話を依頼する窓口を用途や設定に応じて提供する窓口。 */
@Component
public class LlmChatClientProvider {
  /** Slackエージェントツール一覧。 */
  private org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.slack.SlackAgentTools>
      slackAgentTools;

  /** 用途に応じたモデル利用で使用するSlackエージェントツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setSlackAgentTools(
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.slack.SlackAgentTools>
          tools) {
    this.slackAgentTools = tools;
  }

  /** 実行予定の管理ツール一覧。 */
  private dev.mikoto2000.rei.temporal.SchedulerTools schedulerTools;

  /** 用途に応じたモデル利用で使用する実行予定の管理ツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setSchedulerTools(dev.mikoto2000.rei.temporal.SchedulerTools tools) {
    this.schedulerTools = tools;
  }

  /** 依存条件ツール一覧。 */
  private dev.mikoto2000.rei.core.dependency.DependencyTools dependencyTools;

  /** 用途に応じたモデル利用で使用する依存条件ツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setDependencyTools(dev.mikoto2000.rei.core.dependency.DependencyTools tools) {
    this.dependencyTools = tools;
  }

  /** ファイル依存条件ツール一覧。 */
  private dev.mikoto2000.rei.core.FileDependencyTools fileDependencyTools;

  /** 用途に応じたモデル利用で使用するファイル依存条件ツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setFileDependencyTools(dev.mikoto2000.rei.core.FileDependencyTools tools) {
    this.fileDependencyTools = tools;
  }

  /** プロセス依存条件ツール一覧。 */
  private dev.mikoto2000.rei.core.ProcessDependencyTools processDependencyTools;

  /** 用途に応じたモデル利用で使用するプロセス依存条件ツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setProcessDependencyTools(dev.mikoto2000.rei.core.ProcessDependencyTools tools) {
    this.processDependencyTools = tools;
  }

  /** 論文ツール一覧。 */
  private ObjectProvider<dev.mikoto2000.rei.paper.PaperTools> paperTools;

  /** 用途に応じたモデル利用で使用する論文ツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setPaperTools(ObjectProvider<dev.mikoto2000.rei.paper.PaperTools> tools) {
    this.paperTools = tools;
  }

  /** 要約ツール一覧。 */
  private ObjectProvider<SummaryTools> summaryTools;

  /** 用途に応じたモデル利用で使用する要約ツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setSummaryTools(ObjectProvider<SummaryTools> tools) {
    this.summaryTools = tools;
  }

  /** 作業状況ツール一覧。 */
  private ObjectProvider<dev.mikoto2000.rei.activity.ActivityTools> activityTools;

  /** 用途に応じたモデル利用で使用する作業状況ツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setActivityTools(ObjectProvider<dev.mikoto2000.rei.activity.ActivityTools> tools) {
    this.activityTools = tools;
  }

  /** 文脈文脈の組み立て。 */
  private dev.mikoto2000.rei.core.contextbudget.ContextAssembler contextAssembler;

  /** 文脈履歴。 */
  private dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor contextHistory;

  /** レビューツール一覧。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private dev.mikoto2000.rei.review.ReviewTools reviewTools;

  /** 元の結果ツール一覧。 */
  private dev.mikoto2000.rei.core.contextbudget.RawToolResultTools rawResultTools;

  /** 用途に応じたモデル利用で使用する文脈圧縮を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setContextCompression(
      dev.mikoto2000.rei.core.contextbudget.ContextAssembler assembler,
      dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor history,
      dev.mikoto2000.rei.core.contextbudget.RawToolResultTools rawTools,
      dev.mikoto2000.rei.core.contextbudget.ContextCompressionProperties properties) {
    // Session continuation must load persisted history even when compression is disabled.
    contextHistory = history;
    contextAssembler = assembler;
    rawResultTools = rawTools;
  }

  /** 状態表示ツール一覧。 */
  private ObjectProvider<dev.mikoto2000.rei.dot.DotTools> dotTools;

  /** 用途に応じたモデル利用で使用する状態表示ツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setDotTools(ObjectProvider<dev.mikoto2000.rei.dot.DotTools> tools) {
    dotTools = tools;
  }

  /** 外部エージェントツール一覧。 */
  private dev.mikoto2000.rei.externalagent.ExternalAgentTools externalAgentTools;

  /** 外部委譲。 */
  private dev.mikoto2000.rei.externalagent.ExternalAgentDelegationService externalDelegation;

  /** 用途に応じたモデル利用で使用する外部エージェントツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setExternalAgentTools(
      dev.mikoto2000.rei.externalagent.ExternalAgentTools tools,
      dev.mikoto2000.rei.externalagent.ExternalAgentDelegationService service) {
    this.externalAgentTools = tools;
    this.externalDelegation = service;
  }

  /** モデル提供元。 */
  private final LlmModelProvider modelProvider;

  /** core設定。 */
  private final CoreProperties coreProperties;

  /** システムへの指示サービス。 */
  private final SystemPromptService systemPromptService;

  /** チャット記憶。 */
  private final ChatMemory chatMemory;

  /** ツール一覧。 */
  private final ObjectProvider<Tools> tools;

  /** Google予定表ツール一覧。 */
  private final ObjectProvider<GoogleCalendarTools> googleCalendarTools;

  /** タスクツール一覧。 */
  private final ObjectProvider<TaskTools> taskTools;

  /** 日次案内ツール一覧。 */
  private final ObjectProvider<BriefingTools> briefingTools;

  /** フィードツール一覧。 */
  private final ObjectProvider<FeedTools> feedTools;

  /** リマインダーツール一覧。 */
  private final ObjectProvider<ReminderTools> reminderTools;

  /** 検索ツール一覧。 */
  private final ObjectProvider<SearchTools> searchTools;

  /** Web検索ツール一覧。 */
  private final ObjectProvider<WebSearchTools> webSearchTools;

  /** 音通知通知ツール一覧。 */
  private final ObjectProvider<SoundNotificationTools> soundNotificationTools;

  /** Bluesky投稿ツール一覧。 */
  private final ObjectProvider<BlueskyPostTools> blueskyPostTools;

  /** URL内容取得ツール一覧。 */
  private final ObjectProvider<UrlContentFetchTools> urlContentFetchTools;

  /** テキストツール一覧。 */
  private final ObjectProvider<TextTools> textTools;

  /** 実行環境文脈対話の補助。 */
  private final ObjectProvider<RuntimeContextAdvisor> runtimeContextAdvisor;

  /** エージェントスキル対話の補助。 */
  private final ObjectProvider<AgentSkillAdvisor> agentSkillAdvisor;

  /** 作業中集合対話の補助。 */
  private final ObjectProvider<WorkingSetAdvisor> workingSetAdvisor;

  /** タスク状態対話の補助。 */
  private final ObjectProvider<TaskStateAdvisor> taskStateAdvisor;

  /** タスク状態ツール一覧。 */
  private final ObjectProvider<TaskStateTools> taskStateTools;

  /** ツールイベント完了時の処理提供元。 */
  private final ObjectProvider<ToolEventCallbackProvider> toolEventCallbackProvider;

  /** 実行状況の通知を生成する窓口。 */
  private final AgentEventFactory eventFactory;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** キャッシュ。 */
  private final Map<String, ChatClient> cache = new ConcurrentHashMap<>();

  /** 記憶文脈。 */
  private ObjectProvider<dev.mikoto2000.rei.memory.service.MemoryContextAdvisor> memoryContext;

  /** 作業文脈。 */
  private ObjectProvider<dev.mikoto2000.rei.workcontext.WorkContextAdvisor> workContext;

  /** 作業ツール一覧。 */
  private ObjectProvider<dev.mikoto2000.rei.workcontext.WorkContextTools> workTools;

  /** 経験の記録ツール一覧。 */
  private ObjectProvider<dev.mikoto2000.rei.episode.EpisodeTools> episodeTools;

  /** 用途に応じたモデル利用で使用する経験の記録ツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setEpisodeTools(ObjectProvider<dev.mikoto2000.rei.episode.EpisodeTools> tools) {
    episodeTools = tools;
  }

  /** 再開文脈。 */
  private ObjectProvider<dev.mikoto2000.rei.checkpoint.ResumeContextAdvisor> resumeContext;

  /** 再開用の記録ツール一覧。 */
  private ObjectProvider<dev.mikoto2000.rei.checkpoint.CheckpointTools> checkpointTools;

  /** 用途に応じたモデル利用で使用する再開用の記録を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setCheckpoint(
      ObjectProvider<dev.mikoto2000.rei.checkpoint.ResumeContextAdvisor> advisor,
      ObjectProvider<dev.mikoto2000.rei.checkpoint.CheckpointTools> tools) {
    resumeContext = advisor;
    checkpointTools = tools;
  }

  /** 用途に応じたモデル利用で使用する作業文脈を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setWorkContext(
      ObjectProvider<dev.mikoto2000.rei.workcontext.WorkContextAdvisor> advisor,
      ObjectProvider<dev.mikoto2000.rei.workcontext.WorkContextTools> tools) {
    workContext = advisor;
    workTools = tools;
  }

  /** 用途に応じたモデル利用で使用する記憶文脈を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setMemoryContext(
      ObjectProvider<dev.mikoto2000.rei.memory.service.MemoryContextAdvisor> advisor) {
    memoryContext = advisor;
  }

  /** 画面操作利用ツール一覧。 */
  private ObjectProvider<dev.mikoto2000.rei.computeruse.ComputerUseTools> computerUseTools;

  /** 用途に応じたモデル利用で使用する画面操作利用ツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setComputerUseTools(ObjectProvider<dev.mikoto2000.rei.computeruse.ComputerUseTools> tools) {
    this.computerUseTools = tools;
  }

  /** 補助エージェントツール一覧。 */
  private ObjectProvider<dev.mikoto2000.rei.subagent.SubAgentTools> subAgentTools;

  /** 補助エージェント設定。 */
  private dev.mikoto2000.rei.subagent.SubAgentProperties subAgentProperties =
      new dev.mikoto2000.rei.subagent.SubAgentProperties();

  /** 用途に応じたモデル利用で使用する補助エージェント設定を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setSubAgentProperties(dev.mikoto2000.rei.subagent.SubAgentProperties settings) {
    this.subAgentProperties = settings;
  }

  /** 用途に応じたモデル利用で使用する補助エージェントツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setSubAgentTools(ObjectProvider<dev.mikoto2000.rei.subagent.SubAgentTools> tools) {
    this.subAgentTools = tools;
  }

  /** 指定された値や利用先を使って、LlmChatClientProviderを初期化する。 */
  public LlmChatClientProvider(
      LlmModelProvider modelProvider,
      CoreProperties coreProperties,
      SystemPromptService systemPromptService,
      ChatMemory chatMemory,
      ObjectProvider<Tools> tools,
      ObjectProvider<GoogleCalendarTools> googleCalendarTools,
      ObjectProvider<TaskTools> taskTools,
      ObjectProvider<BriefingTools> briefingTools,
      ObjectProvider<FeedTools> feedTools,
      ObjectProvider<ReminderTools> reminderTools,
      ObjectProvider<SearchTools> searchTools,
      ObjectProvider<WebSearchTools> webSearchTools,
      ObjectProvider<SoundNotificationTools> soundNotificationTools,
      ObjectProvider<BlueskyPostTools> blueskyPostTools,
      ObjectProvider<UrlContentFetchTools> urlContentFetchTools,
      ObjectProvider<TextTools> textTools,
      ObjectProvider<RuntimeContextAdvisor> runtimeContextAdvisor,
      ObjectProvider<AgentSkillAdvisor> agentSkillAdvisor,
      ObjectProvider<WorkingSetAdvisor> workingSetAdvisor,
      ObjectProvider<TaskStateAdvisor> taskStateAdvisor,
      ObjectProvider<TaskStateTools> taskStateTools,
      ObjectProvider<ToolEventCallbackProvider> toolEventCallbackProvider,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
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
    this.textTools = textTools;
    this.runtimeContextAdvisor = runtimeContextAdvisor;
    this.agentSkillAdvisor = agentSkillAdvisor;
    this.workingSetAdvisor = workingSetAdvisor;
    this.taskStateAdvisor = taskStateAdvisor;
    this.taskStateTools = taskStateTools;
    this.toolEventCallbackProvider = toolEventCallbackProvider;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
  }

  /** 用途に応じたモデル利用で用いるモデルへ対話を依頼する窓口を返す。 */
  public ChatClient chatClient(String feature) {
    return cache.computeIfAbsent(feature, this::createChatClient);
  }

  /**
   * 用途に応じたモデル利用で用いる並列モデルへ対話を依頼する窓口を返す。
   *
   * <p>Restricted runs deliberately have no advisors that mutate conversation or task state.
   */
  public ChatClient parallelChatClient(dev.mikoto2000.rei.core.chat.AgentRunContext.Mode mode) {
    if (mode == dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE)
      return chatClient(LlmFeature.CHAT);
    return cache.computeIfAbsent(
        "parallel-" + mode,
        ignored -> {
          var options = modelProvider.chatOptions(LlmFeature.CHAT, null);
          dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoRawTools(options);
          var callbacks = new ArrayList<org.springframework.ai.tool.ToolCallback>();
          if (mode == dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.READ_ONLY) {
            var objects = new ArrayList<Object>();
            addIfAvailable(objects, tools);
            addIfAvailable(objects, searchTools);
            addIfAvailable(objects, webSearchTools);
            addIfAvailable(objects, urlContentFetchTools);
            if (rawResultTools != null) objects.add(rawResultTools);
            if (reviewTools != null) objects.add(reviewTools);
            if (!objects.isEmpty()) {
              for (var callback :
                  dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration.provider(
                          objects.toArray())
                      .getToolCallbacks()) {
                if (dev.mikoto2000.rei.core.policy.ToolPermissionPolicy.intrinsicallyReadOnly(
                    callback.getToolDefinition().name()))
                  callbacks.add(
                      new dev.mikoto2000.rei.event.ToolEventCallbackDecorator(
                          callback, eventFactory, eventPublisher));
              }
            }
          }
          var builder =
              ChatClient.builder(
                      new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(
                          modelProvider.subAgentChatModel(), contextAssembler))
                  .defaultAdvisors(new RunAwareToolCallingAdvisor())
                  .defaultOptions(
                      options
                          .mutate()
                          .toolCallbacks(callbacks)
                          .toolChoice(callbacks.isEmpty() ? "none" : "auto"));
          if (mode == dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.READ_ONLY)
            builder.defaultSystem(
                dev.mikoto2000.rei.core.configuration.ToolUsagePolicy.INSTRUCTIONS);
          return builder.build();
        });
  }

  /** 新たな対象を利用できるよう、モデルへ対話を依頼する窓口を作成する。 */
  private ChatClient createChatClient(String feature) {
    // Extraction and summarization process supplied evidence without conversation state or tools.
    if (LlmFeature.MEMORY.equals(feature) || LlmFeature.VOICE_CORRECTION.equals(feature)) {
      var options = modelProvider.chatOptions(feature, null);
      dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoRawTools(options);
      return ChatClient.builder(
              LlmFeature.VOICE_CORRECTION.equals(feature)
                  ? modelProvider.voiceCorrectionChatModel()
                  : modelProvider.memoryChatModel())
          .defaultOptions(options.mutate().toolCallbacks(List.of()).toolChoice("none"))
          .build();
    }
    List<Advisor> advisors = new ArrayList<>();
    if (LlmFeature.CHAT.equals(feature)
        && resumeContext != null
        && resumeContext.getIfAvailable() != null) advisors.add(resumeContext.getObject());
    if (LlmFeature.CHAT.equals(feature)
        && workContext != null
        && workContext.getIfAvailable() != null) advisors.add(workContext.getObject());
    if (LlmFeature.CHAT.equals(feature)
        && memoryContext != null
        && memoryContext.getIfAvailable() != null) advisors.add(memoryContext.getObject());
    if (LlmFeature.CHAT.equals(feature) && externalDelegation != null)
      advisors.add(
          new dev.mikoto2000.rei.externalagent.ExternalAgentReviewAdvisor(
              externalDelegation, chatMemory));
    if (LlmFeature.CHAT.equals(feature) && contextHistory != null) advisors.add(contextHistory);
    else
      advisors.add(
          MessageChatMemoryAdvisor.builder(chatMemory)
              .scheduler(BaseAdvisor.DEFAULT_SCHEDULER)
              .build());
    RuntimeContextAdvisor runtimeContextAdvisorInstance = runtimeContextAdvisor.getIfAvailable();
    if (runtimeContextAdvisorInstance != null) {
      advisors.add(runtimeContextAdvisorInstance);
    }
    if (supportsAgentSkills(feature)) {
      AgentSkillAdvisor skillAdvisor = agentSkillAdvisor.getIfAvailable();
      if (skillAdvisor != null) {
        advisors.add(skillAdvisor);
      }
    }
    WorkingSetAdvisor workingSetAdvisorInstance = workingSetAdvisor.getIfAvailable();
    if (workingSetAdvisorInstance != null) {
      advisors.add(workingSetAdvisorInstance);
    }
    TaskStateAdvisor taskStateAdvisorInstance = taskStateAdvisor.getIfAvailable();
    if (taskStateAdvisorInstance != null) {
      advisors.add(taskStateAdvisorInstance);
    }
    ChatClient.Builder builder =
        ChatClient.builder(
                new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(
                    modelProvider.chatModel(feature),
                    LlmFeature.CHAT.equals(feature) ? contextAssembler : null))
            .defaultAdvisors(new RunAwareToolCallingAdvisor())
            .defaultSystem(
                dev.mikoto2000.rei.core.configuration.ToolUsagePolicy.appendTo(
                    systemPromptService.systemPrompt()))
            .defaultOptions(modelProvider.chatOptions(feature, null).mutate())
            .defaultAdvisors(dev.mikoto2000.rei.core.chat.RunScopedAdvisor.wrap(advisors));

    // Reply generation returns text; only the caller may publish the reply.
    if (LlmFeature.BLUESKY_REPLY.equals(feature)) return builder.build();

    List<Object> toolObjects = new ArrayList<>();
    if (LlmFeature.CHAT.equals(feature) && checkpointTools != null)
      addIfAvailable(toolObjects, checkpointTools);
    if (LlmFeature.CHAT.equals(feature) && workTools != null)
      addIfAvailable(toolObjects, workTools);
    if (LlmFeature.CHAT.equals(feature) && episodeTools != null)
      addIfAvailable(toolObjects, episodeTools);
    if (LlmFeature.CHAT.equals(feature) && paperTools != null)
      addIfAvailable(toolObjects, paperTools);
    if (LlmFeature.CHAT.equals(feature) && summaryTools != null)
      addIfAvailable(toolObjects, summaryTools);
    if (LlmFeature.CHAT.equals(feature) && activityTools != null)
      addIfAvailable(toolObjects, activityTools);
    if (LlmFeature.CHAT.equals(feature) && rawResultTools != null) toolObjects.add(rawResultTools);
    if (LlmFeature.CHAT.equals(feature) && reviewTools != null) toolObjects.add(reviewTools);
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
    addIfAvailable(toolObjects, textTools);
    TaskStateTools taskStateToolsInstance = taskStateTools.getIfAvailable();
    if (taskStateToolsInstance != null) {
      toolObjects.add(taskStateToolsInstance);
    }
    if (LlmFeature.CHAT.equals(feature) && processDependencyTools != null)
      toolObjects.add(processDependencyTools);
    if (LlmFeature.CHAT.equals(feature) && fileDependencyTools != null)
      toolObjects.add(fileDependencyTools);
    if (LlmFeature.CHAT.equals(feature) && dependencyTools != null)
      toolObjects.add(dependencyTools);
    if (LlmFeature.CHAT.equals(feature) && schedulerTools != null) toolObjects.add(schedulerTools);
    if (!toolObjects.isEmpty()) {
      var methodTools =
          dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration.provider(
              toolObjects.toArray());
      builder.defaultTools(
          new ToolEventCallbackProvider(methodTools, eventFactory, eventPublisher));
    }

    ToolEventCallbackProvider toolCallbackProvider = toolEventCallbackProvider.getIfAvailable();
    if (toolCallbackProvider != null) {
      builder.defaultTools(toolCallbackProvider);
    }
    if (LlmFeature.CHAT.equals(feature) && externalAgentTools != null)
      builder.defaultTools((Object[]) externalAgentTools.callbacks());
    if (LlmFeature.CHAT.equals(feature) && dotTools != null && dotTools.getIfAvailable() != null)
      builder.defaultTools(java.util.Arrays.asList(dotTools.getObject().callbacks()));
    if (LlmFeature.CHAT.equals(feature)
        && slackAgentTools != null
        && slackAgentTools.getIfAvailable() != null)
      builder.defaultTools((Object[]) slackAgentTools.getObject().callbacks());
    if (LlmFeature.CHAT.equals(feature)
        && subAgentTools != null
        && subAgentTools.getIfAvailable() != null) {
      for (String name :
          dev.mikoto2000.rei.subagent.SubAgentParentToolCatalog.names(subAgentProperties))
        builder.defaultTools(
            new dev.mikoto2000.rei.event.ToolEventCallbackDecorator(
                subAgentTools.getObject().callback(name), eventFactory, eventPublisher));
    }
    if (LlmFeature.CHAT.equals(feature) && computerUseTools != null) {
      var computer = computerUseTools.getIfAvailable();
      if (computer != null) {
        builder.defaultTools(computer.callback());
        builder.defaultSystem(
            dev.mikoto2000.rei.core.configuration.ToolUsagePolicy.appendTo(
                    systemPromptService.systemPrompt())
                + "\n\n"
                + computer.orchestrationPrompt());
      }
    }
    return builder.build();
  }

  /** 用途に応じたモデル利用において、エージェントスキル一覧への対応可否を返す。 */
  static boolean supportsAgentSkills(String feature) {
    return LlmFeature.CHAT.equals(feature);
  }

  /** 新たな対象を管理できるよう、条件付き利用可能を追加する。 */
  private void addIfAvailable(List<Object> toolObjects, ObjectProvider<?> provider) {
    Object tool = provider.getIfAvailable();
    if (tool != null) {
      toolObjects.add(tool);
    }
  }
}
