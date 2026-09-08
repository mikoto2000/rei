package dev.mikoto2000.rei.core.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.core.command.InlineFileAttachmentResolver;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.event.ErrorInformation;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.llm.ConversationIds;
import dev.mikoto2000.rei.llm.FixedLlmChatClientProvider;
import dev.mikoto2000.rei.llm.FixedLlmModelProvider;
import dev.mikoto2000.rei.llm.LlmChatClientProvider;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.llm.OutputLimitDetector;
import dev.mikoto2000.rei.llm.OutputLimitReplanPlan;
import dev.mikoto2000.rei.llm.OutputLimitReplanRequest;
import dev.mikoto2000.rei.llm.OutputLimitReplanSubgoal;
import dev.mikoto2000.rei.llm.OutputLimitReplanner;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.core.working.WorkingSet;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import dev.mikoto2000.rei.core.actionplan.ActionPlan;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.skills.AgentSkillAdvisor;
import dev.mikoto2000.rei.skills.SkillRoutingRunContext;
import dev.mikoto2000.rei.topic.AgentActivityTracker;
import dev.mikoto2000.rei.topic.TopicOrchestrator;
import reactor.core.Disposable;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import dev.mikoto2000.rei.memory.service.MemoryConsolidatorService;

@Component
public class ChatExecutionService {

  private static final Logger log = LoggerFactory.getLogger(ChatExecutionService.class);

  private final LlmChatClientProvider chatClientProvider;

  private final ModelHolderService currentModelHolder;
  private final LlmModelProvider modelProvider;
  private final LlmProperties llmProperties;

  private final CommandCancellationService cancellationService;

  private final Optional<MemoryConsolidatorService> memoryConsolidatorService;
  private final Optional<OutputLimitReplanner> outputLimitReplanner;
  private final Optional<TopicOrchestrator> topicOrchestrator;
  private final Optional<WorkingSet> workingSet;
  private final Clock clock;
  private final Optional<AgentActivityTracker> activityTracker;
  private final AgentEventFactory eventFactory;
  private final AgentEventPublisher eventPublisher;
  private final InlineFileAttachmentResolver inlineFileAttachmentResolver = new InlineFileAttachmentResolver();
  private ConversationLogStore conversationLogStore;
  private ProjectService projectService;
  private ActionPlan actionPlan;
  private ChatMemory chatMemory;

  @Autowired
  void setChatMemory(ChatMemory chatMemory) { this.chatMemory = chatMemory; }

  @Autowired(required = false)
  void setExecutionState(ProjectService projectService, ActionPlan actionPlan) {
    this.projectService = projectService;
    this.actionPlan = actionPlan;
  }

  @Autowired
  void setConversationLogStore(ConversationLogStore conversationLogStore) {
    this.conversationLogStore = conversationLogStore;
  }

  public ChatExecutionService(ChatClient chatClient, ModelHolderService currentModelHolder,
      CommandCancellationService cancellationService,
      Optional<MemoryConsolidatorService> memoryConsolidatorService) {
    this(new FixedLlmChatClientProvider(chatClient), currentModelHolder, new FixedLlmModelProvider(),
        new LlmProperties(), cancellationService, memoryConsolidatorService, Optional.empty(),
        Optional.empty(), Optional.empty(), Clock.systemDefaultZone(), Optional.empty(),
        new AgentEventFactory(Clock.systemDefaultZone()), new InMemoryAgentEventBus());
  }

  public ChatExecutionService(ChatClient chatClient, ModelHolderService currentModelHolder,
      CommandCancellationService cancellationService,
      Optional<MemoryConsolidatorService> memoryConsolidatorService, AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    this(new FixedLlmChatClientProvider(chatClient), currentModelHolder, new FixedLlmModelProvider(),
        new LlmProperties(), cancellationService, memoryConsolidatorService, Optional.empty(),
        Optional.empty(), Optional.empty(), Clock.systemDefaultZone(), Optional.empty(), eventFactory, eventPublisher);
  }

  public ChatExecutionService(LlmChatClientProvider chatClientProvider, ModelHolderService currentModelHolder,
      LlmModelProvider modelProvider, LlmProperties llmProperties, CommandCancellationService cancellationService,
      Optional<MemoryConsolidatorService> memoryConsolidatorService,
      Optional<OutputLimitReplanner> outputLimitReplanner) {
    this(chatClientProvider, currentModelHolder, modelProvider, llmProperties, cancellationService,
        memoryConsolidatorService, outputLimitReplanner,
        Optional.empty(), Optional.empty(), Clock.systemDefaultZone(), Optional.empty(),
        new AgentEventFactory(Clock.systemDefaultZone()), new InMemoryAgentEventBus());
  }

  @Autowired
  public ChatExecutionService(LlmChatClientProvider chatClientProvider, ModelHolderService currentModelHolder,
      LlmModelProvider modelProvider, LlmProperties llmProperties, CommandCancellationService cancellationService,
      Optional<MemoryConsolidatorService> memoryConsolidatorService,
      Optional<OutputLimitReplanner> outputLimitReplanner,
      Optional<TopicOrchestrator> topicOrchestrator, Optional<WorkingSet> workingSet, Clock clock,
      Optional<AgentActivityTracker> activityTracker,
      AgentEventFactory eventFactory, AgentEventPublisher eventPublisher) {
    this.chatClientProvider = chatClientProvider;
    this.currentModelHolder = currentModelHolder;
    this.modelProvider = modelProvider;
    this.llmProperties = llmProperties;
    this.cancellationService = cancellationService;
    this.memoryConsolidatorService = memoryConsolidatorService;
    this.outputLimitReplanner = outputLimitReplanner;
    this.topicOrchestrator = topicOrchestrator;
    this.workingSet = workingSet;
    this.clock = clock;
    this.activityTracker = activityTracker;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
  }

  public ChatExecutionResult execute(String promptText) {
    String conversationId = ConversationIds.currentChat();
    return execute(new AgentRunContext(UUID.randomUUID().toString(), conversationId,
        projectService == null ? java.nio.file.Path.of(".") : projectService.currentProject(),
        dev.mikoto2000.rei.core.project.ProjectStorage.projectId(conversationId)),
        promptText, new UserInterventionQueue());
  }

  public ChatExecutionResult execute(AgentRunContext context, String promptText, UserInterventionQueue interventions) {
    try (var scope = AgentRunScope.open(context)) {
      return executeInScope(context, promptText, interventions);
    }
  }

  private ChatExecutionResult executeInScope(AgentRunContext context, String promptText, UserInterventionQueue interventions) {
    long startedAtNanos = System.nanoTime();
    cancellationService.begin(Thread.currentThread());
    String runId = context.runId();
    SkillRoutingRunContext skillRoutingContext = new SkillRoutingRunContext(runId);
    AtomicLong runCompletionTokens = new AtomicLong();
    AtomicBoolean usageAvailable = new AtomicBoolean();
    AtomicReference<GenerationMetrics> lastGenerationMetrics = new AtomicReference<>();
    OutputLimitRunBudget budget = new OutputLimitRunBudget(
        llmProperties.getOutputLimit().getMaxReplansPerGoal(),
        llmProperties.getOutputLimit().getMaxLlmCallsPerRun());
    RunExecutionContext execution = new RunExecutionContext(runId, budget,
        new ProgressEvaluator(context.projectRoot(),
            actionPlan), eventFactory, eventPublisher);
    execution.setRunContext(context);
    execution.setInterventions(interventions, text -> {
      if (chatMemory != null) chatMemory.add(context.conversationId(), java.util.List.of(new UserMessage(text)));
      appendConversationLog(context.conversationId(), "user", text);
    });

    try {
      activityTracker.ifPresent(tracker -> tracker.recordUserActivity(java.time.Instant.now(clock)));
      appendConversationLog(context.conversationId(), "user", promptText);
      if (!budget.tryConsumeLlmCall()) {
        log.warn("Chat skipped: LLM call budget exhausted before initial prompt");
        return ChatExecutionResult.failed("LLM call budget exhausted before initial prompt");
      }
      eventPublisher.publish(eventFactory.runStarted(runId, "user-request", null));
      activityTracker.ifPresent(tracker -> tracker.recordAgentStarted(java.time.Instant.now(clock)));
      ChatRunResult result = executePrompt(promptText, true, startedAtNanos, budget, execution, runId, skillRoutingContext,
          runCompletionTokens, usageAvailable, lastGenerationMetrics);
      if (result.status() == ChatRunStatus.OUTPUT_LIMIT) {
        result = handleOutputLimit(promptText, promptText, "", result.text(), budget, execution, startedAtNanos, runId,
            skillRoutingContext,
            runCompletionTokens, usageAvailable, lastGenerationMetrics);
      }
      while (result.status() == ChatRunStatus.SUCCESS && !interventions.finishIfEmpty()) {
        var guidance = execution.applyInterventions();
        if (!budget.tryConsumeLlmCall()) { result = new ChatRunResult(ChatRunStatus.LLM_CALL_BUDGET_EXCEEDED, ""); break; }
        result = executePrompt(guidance.stream().map(org.springframework.ai.chat.messages.Message::getText)
            .collect(java.util.stream.Collectors.joining("\n")), false, startedAtNanos, budget, execution, runId,
            skillRoutingContext, runCompletionTokens, usageAvailable, lastGenerationMetrics);
      }
      if (result.status() == ChatRunStatus.SUCCESS) {
        appendConversationLog(context.conversationId(), "assistant", result.text());
        GenerationMetrics metrics = lastGenerationMetrics.get();
        eventPublisher.publish(eventFactory.runCompleted(runId, elapsedMillis(startedAtNanos),
            usageAvailable.get() ? runCompletionTokens.get() : null,
            metrics == null ? null : metrics.timeToFirstTokenMillis(),
            metrics == null ? null : metrics.outputTokensPerSecond(),
            metrics == null ? null : metrics.endToEndTokensPerSecond()));
        activityTracker.ifPresent(tracker -> tracker.recordAgentCompleted(java.time.Instant.now(clock)));
        boolean consolidationSuggested = shouldSuggestConsolidation();
        if (consolidationSuggested) {
          eventPublisher.publish(eventFactory.memoryConsolidationSuggested());
        }
        maybeRefreshTopicCandidates();
        return ChatExecutionResult.success(result.text(), consolidationSuggested);
      } else {
        eventPublisher.publish(eventFactory.runFailed(runId, terminalError(result.status())));
        activityTracker.ifPresent(tracker -> tracker.recordAgentCompleted(java.time.Instant.now(clock)));
        return ChatExecutionResult.failed(terminalError(result.status()).message());
      }
    } finally {
      // Accepted input remains part of history even when cancellation or a hard budget stops the run.
      while (!interventions.finishIfEmpty()) execution.applyInterventions();
      execution.close();
      cancellationService.clear();
    }
  }

  private void appendConversationLog(String conversationId, String speaker, String content) {
    if (conversationLogStore != null) {
      conversationLogStore.append(conversationId, speaker, content);
    }
  }

  private ErrorInformation terminalError(ChatRunStatus status) {
    return switch (status) {
      case OUTPUT_LIMIT -> new ErrorInformation("OutputLimit", "output token limit reached", "output_limit");
      case STAGNATED -> new ErrorInformation("Stagnated", "STAGNATED: no meaningful progress after replanning", "stagnated");
      case LLM_CALL_BUDGET_EXCEEDED -> new ErrorInformation("LlmCallBudgetExceeded", "LLM call budget exceeded", "llm_call_budget_exceeded");
      case REPLAN_BUDGET_EXCEEDED -> new ErrorInformation("ReplanBudgetExceeded", "replan hard budget exceeded", "replan_budget_exceeded");
      case CANCELLED -> new ErrorInformation("Cancelled", "chat run cancelled", "cancelled");
      case FAILED -> new ErrorInformation("ChatRunFailed", "chat run failed", null);
      case SUCCESS -> throw new IllegalArgumentException("SUCCESS is not a failed terminal state");
    };
  }

  private ChatRunResult handleOutputLimit(String originalUserRequest, String currentGoal, String progressSoFar,
      String partialOutput, OutputLimitRunBudget budget, RunExecutionContext execution, long startedAtNanos, String runId,
      SkillRoutingRunContext skillRoutingContext,
      AtomicLong runCompletionTokens, AtomicBoolean usageAvailable,
      AtomicReference<GenerationMetrics> lastGenerationMetrics) {
    if (outputLimitReplanner.isEmpty()) {
      return ChatRunResult.outputLimit(partialOutput);
    }
    if (!budget.hasRemainingLlmCalls()) {
      log.warn("Output limit replan skipped: goal={}, reason=llm_call_budget_exhausted_before_planner",
          summarizeForLog(currentGoal));
      return new ChatRunResult(ChatRunStatus.LLM_CALL_BUDGET_EXCEEDED, partialOutput);
    }
    if (!budget.tryConsumeReplan()) {
      log.warn("Output limit replan skipped: goal={}, reason=replan_budget_exhausted, replanCount={}",
          summarizeForLog(currentGoal), budget.replanCount());
      return new ChatRunResult(ChatRunStatus.REPLAN_BUDGET_EXCEEDED, partialOutput);
    }
    if (!budget.tryConsumeLlmCall()) {
      log.warn("Output limit replan skipped: goal={}, reason=llm_call_budget_exhausted_before_planner",
          summarizeForLog(currentGoal));
      return new ChatRunResult(ChatRunStatus.LLM_CALL_BUDGET_EXCEEDED, partialOutput);
    }

    OutputLimitReplanPlan plan;
    try {
      log.info("Output limit replan started: goal={}, replanCount={}, remainingLlmCalls={}",
          summarizeForLog(currentGoal), budget.replanCount(), budget.remainingLlmCalls());
      plan = outputLimitReplanner.get().replan(new OutputLimitReplanRequest(
          originalUserRequest,
          currentGoal,
          progressSoFar,
          partialOutput,
          budget.replanCount(),
          llmProperties.getOutputLimit().getMaxReplansPerGoal(),
          budget.remainingLlmCalls()));
    } catch (Exception e) {
      log.warn("Output limit replan failed", e);
      return ChatRunResult.outputLimit(partialOutput);
    }

    StringBuilder subgoalResults = new StringBuilder();
    for (OutputLimitReplanSubgoal subgoal : plan.subgoals()) {
      if (!budget.tryConsumeLlmCall()) {
        log.warn("Output limit subgoal skipped: LLM call budget exhausted");
        return new ChatRunResult(ChatRunStatus.LLM_CALL_BUDGET_EXCEEDED, subgoalResults.toString());
      }
      log.info("Output limit subgoal started: id={}, goal={}", subgoal.id(), subgoal.goal());
      ChatRunResult subgoalResult = executePrompt(subgoal.goal(), false, startedAtNanos, budget, execution, runId,
          skillRoutingContext,
           runCompletionTokens, usageAvailable, lastGenerationMetrics);
      log.info("Output limit subgoal finished: id={}, status={}", subgoal.id(), subgoalResult.status());
      if (subgoalResult.status() == ChatRunStatus.OUTPUT_LIMIT) {
        subgoalResult = handleOutputLimit(originalUserRequest, subgoal.goal(), subgoalResults.toString(),
            subgoalResult.text(), budget, execution, startedAtNanos, runId, skillRoutingContext, runCompletionTokens, usageAvailable,
             lastGenerationMetrics);
      }
      if (subgoalResult.status() != ChatRunStatus.SUCCESS) {
        return subgoalResult;
      }
      execution.completeSubgoal(subgoal.goal());
      subgoalResults.append("## ").append(subgoal.id()).append("\n")
          .append(subgoalResult.text()).append("\n\n");
    }

    if (!budget.tryConsumeLlmCall()) {
      log.warn("Output limit final integration skipped: LLM call budget exhausted");
      return new ChatRunResult(ChatRunStatus.LLM_CALL_BUDGET_EXCEEDED, subgoalResults.toString());
    }
    return executePrompt(buildIntegrationPrompt(originalUserRequest, plan.finalGoal(), subgoalResults.toString()),
        false, startedAtNanos, budget, execution, runId, skillRoutingContext, runCompletionTokens, usageAvailable,
        lastGenerationMetrics);
  }

  private ChatRunResult executePrompt(String promptText, boolean resolveAttachments, long startedAtNanos,
      OutputLimitRunBudget budget, RunExecutionContext execution, String runId, SkillRoutingRunContext skillRoutingContext,
      AtomicLong runCompletionTokens, AtomicBoolean usageAvailable,
      AtomicReference<GenerationMetrics> lastGenerationMetrics) {
    InlineFileAttachmentResolver.ResolvedPrompt resolvedPrompt = resolveAttachments
        ? inlineFileAttachmentResolver.resolve(promptText)
        : new InlineFileAttachmentResolver.ResolvedPrompt(promptText, java.util.List.of(), java.util.List.of());
    for (String warning : resolvedPrompt.warnings()) {
      log.warn("Prompt attachment warning: {}", warning);
    }

    var options = modelProvider.chatOptions(LlmFeature.CHAT, currentModelHolder.get(), true);
    options.setToolContext(Map.of(RunExecutionContext.KEY, execution));
    ChatClientRequestSpec requestSpec = chatClientProvider.chatClient(LlmFeature.CHAT)
      .prompt(new Prompt(
          UserMessage.builder()
              .text(resolvedPrompt.prompt())
              .media(resolvedPrompt.media())
              .build(),
          options))
      .advisors(advisor -> advisor
          .param(AgentRunContext.class.getName(), execution.runContext())
          .param(ChatMemory.CONVERSATION_ID, execution.runContext().conversationId())
          .param(AgentSkillAdvisor.ROUTING_CONTEXT_KEY, skillRoutingContext));

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> errorRef = new AtomicReference<>();
    AtomicReference<String> previousThinking = new AtomicReference<>("");
    AtomicBoolean thinkingEventStarted = new AtomicBoolean(false);
    AtomicBoolean thinkingEventCompleted = new AtomicBoolean(false);
    String thinkingId = UUID.randomUUID().toString();
    StringBuilder thinkingBuilder = new StringBuilder();
    AtomicLong answerStartedAtNanos = new AtomicLong(0L);
    AtomicLong answerLastChunkAtNanos = new AtomicLong(0L);
    AtomicLong streamCompletedAtNanos = new AtomicLong(0L);
    AtomicInteger answerChunkCount = new AtomicInteger(0);
    AtomicInteger completionTokens = new AtomicInteger(0);
    AtomicBoolean outputLimitReached = new AtomicBoolean(false);
    AtomicBoolean messageStarted = new AtomicBoolean(false);
    String messageId = UUID.randomUUID().toString();
    StringBuilder responseBuilder = new StringBuilder();
    Disposable disposable;
    long requestStartedAtNanos = System.nanoTime();
    long tokensBeforePrompt = execution.completionTokens();
    try {
      disposable = requestSpec.stream()
        .chatResponse()
        .subscribe(
            response -> {
              try (var scope = AgentRunScope.open(execution.runContext())) {
              if (response.getResult() != null && response.getResult().getMetadata() != null
                  && response.getResult().getMetadata().getFinishReason() != null
                  && !response.getResult().getMetadata().getFinishReason().isBlank()) {
                outputLimitReached.set(OutputLimitDetector.isOutputLimitReached(response));
              }
              captureCompletionTokens(response, completionTokens);
              if (!messageStarted.get()) {
                publishThinking(response, previousThinking, thinkingEventStarted, thinkingId, thinkingBuilder);
              }
              String chunk = answerText(response);
              if (chunk == null || chunk.isEmpty()) {
                return;
              }
              if (messageStarted.compareAndSet(false, true)) {
                completeThinking(thinkingEventStarted, thinkingEventCompleted, thinkingId, thinkingBuilder);
                eventPublisher.publish(eventFactory.messageStarted(messageId, "assistant"));
                answerStartedAtNanos.compareAndSet(0L, System.nanoTime());
              }
              answerLastChunkAtNanos.set(System.nanoTime());
              answerChunkCount.incrementAndGet();
              responseBuilder.append(chunk);
              eventPublisher.publish(eventFactory.messageDelta(messageId, chunk));
              }
            },
            error -> {
              errorRef.set(error);
              latch.countDown();
            },
            () -> {
              streamCompletedAtNanos.set(System.nanoTime());
              latch.countDown();
            });
    } catch (RuntimeException e) {
      log.warn("Chat response stream failed to start", e);
      return failureResult(e);
    }
    cancellationService.register(disposable);

    try {
      latch.await();
      long iterationTokens = execution.completionTokens() - tokensBeforePrompt;
      if (iterationTokens > 0) completionTokens.set((int) Math.min(Integer.MAX_VALUE, iterationTokens));
      if (completionTokens.get() > 0) {
        runCompletionTokens.addAndGet(completionTokens.get());
        usageAvailable.set(true);
      }
      completeThinking(thinkingEventStarted, thinkingEventCompleted, thinkingId, thinkingBuilder);
      Throwable error = errorRef.get();
      if (error != null) {
        ChatRunResult failure = failureResult(error);
        if (failure.status() == ChatRunStatus.FAILED) log.warn("Chat response failed", error);
        else log.info("Chat execution stopped: runId={}, reason={}", runId, failure.status());
        return failure;
      }
      if (outputLimitReached.get()) {
        log.warn("Chat output token limit reached: goal={}, promptLength={}, generatedLength={}",
            summarizeForLog(promptText), promptText.length(), responseBuilder.length());
        return ChatRunResult.outputLimit(responseBuilder.toString());
      }
      if (messageStarted.get()) {
        eventPublisher.publish(eventFactory.messageCompleted(messageId, "assistant", responseBuilder.toString()));
      }
      GenerationMetrics metrics = calculateGenerationMetrics(requestStartedAtNanos, answerStartedAtNanos.get(),
          answerLastChunkAtNanos.get(), streamCompletedAtNanos.get(), completionTokens.get(), answerChunkCount.get());
      lastGenerationMetrics.set(metrics);
      return ChatRunResult.success(responseBuilder.toString());
    } catch (InterruptedException e) {
      completeThinking(thinkingEventStarted, thinkingEventCompleted, thinkingId, thinkingBuilder);
      if (cancellationService.consumeCancellationRequested()) {
        return ChatRunResult.cancelled();
      }
      Thread.currentThread().interrupt();
      log.warn("Chat response wait interrupted", e);
      return ChatRunResult.failed();
    }
  }

  private long elapsedMillis(long startedAtNanos) {
    return (System.nanoTime() - startedAtNanos) / 1_000_000L;
  }

  private ChatRunResult failureResult(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof ExecutionStoppedException stopped) {
        return new ChatRunResult(ChatRunStatus.valueOf(stopped.reason().name()), "");
      }
      if (cause instanceof java.util.concurrent.CancellationException) return ChatRunResult.cancelled();
    }
    return ChatRunResult.failed();
  }

  private String buildIntegrationPrompt(String originalUserRequest, String finalGoal, String subgoalResults) {
    return """
        元のユーザー要求に対する最終回答を作成してください。

        元のユーザー要求:
        %s

        統合ゴール:
        %s

        サブゴール結果:
        %s
        """.formatted(originalUserRequest, finalGoal, subgoalResults);
  }

  private String summarizeForLog(String value) {
    if (value == null) {
      return "";
    }
    String normalized = value.replaceAll("\\s+", " ").trim();
    int maxLength = 120;
    return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
  }

  private boolean shouldSuggestConsolidation() {
    AtomicBoolean suggested = new AtomicBoolean(false);
    memoryConsolidatorService.ifPresent(service -> {
      try {
        if (service.shouldSuggestConsolidationNow()) {
          suggested.set(true);
        }
      } catch (Exception ignored) {
      }
    });
    return suggested.get();
  }

  private void maybeRefreshTopicCandidates() {
    topicOrchestrator.ifPresent(orchestrator -> {
      try {
        orchestrator.onChatCompleted();
      } catch (Exception e) {
        log.warn("Topic candidate refresh failed", e);
      }
    });
  }

  private String buildUserFacingMessage(Throwable error) {
    Throwable root = rootCause(error);
    String message = root.getMessage();
    if (message == null || message.isBlank()) {
      return "回答の取得に失敗しました";
    }
    return "回答の取得に失敗しました: " + message;
  }

  private Throwable rootCause(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private void captureCompletionTokens(ChatResponse response, AtomicInteger completionTokens) {
    if (response == null || response.getMetadata() == null) {
      return;
    }
    Usage usage = response.getMetadata().getUsage();
    if (usage == null || usage.getCompletionTokens() == null || usage.getCompletionTokens() <= 0) {
      return;
    }
    completionTokens.set(usage.getCompletionTokens());
  }

  public static GenerationMetrics calculateGenerationMetrics(long requestStartedAtNanos, long answerStartedAtNanos,
      long answerLastChunkAtNanos, long completedAtNanos, int completionTokens, int answerChunkCount) {
    if (requestStartedAtNanos <= 0L || answerStartedAtNanos <= 0L || completionTokens <= 0) return null;
    double ttftMillis = Math.max((answerStartedAtNanos - requestStartedAtNanos) / 1_000_000.0d, 0.0d);
    double endToEndSeconds = Math.max((completedAtNanos - requestStartedAtNanos) / 1_000_000_000.0d, 0.001d);
    Double outputTokensPerSecond = null;
    if (completionTokens > 1 && answerChunkCount > 1 && answerLastChunkAtNanos > answerStartedAtNanos) {
      double outputSeconds = (answerLastChunkAtNanos - answerStartedAtNanos) / 1_000_000_000.0d;
      outputTokensPerSecond = (completionTokens - 1) / outputSeconds;
    }
    return new GenerationMetrics(ttftMillis, outputTokensPerSecond, completionTokens / endToEndSeconds);
  }

  public record GenerationMetrics(Double timeToFirstTokenMillis, Double outputTokensPerSecond,
      Double endToEndTokensPerSecond) { }

  private void publishThinking(ChatResponse response, AtomicReference<String> previousThinking, AtomicBoolean thinkingEventStarted,
      String thinkingId, StringBuilder thinkingBuilder) {
    String thinking = thinkingText(response);
    if (thinking == null || thinking.isEmpty()) {
      return;
    }
    String delta = thinkingDelta(thinking, previousThinking);
    if (delta.isEmpty()) {
      return;
    }
    if (thinkingEventStarted.compareAndSet(false, true)) {
      eventPublisher.publish(eventFactory.thinkingStarted(thinkingId));
    }
    thinkingBuilder.append(delta);
    eventPublisher.publish(eventFactory.thinkingDelta(thinkingId, delta));
  }

  private void completeThinking(AtomicBoolean thinkingEventStarted, AtomicBoolean thinkingEventCompleted,
      String thinkingId, StringBuilder thinkingBuilder) {
    if (thinkingEventStarted.get() && thinkingEventCompleted.compareAndSet(false, true)) {
      eventPublisher.publish(eventFactory.thinkingCompleted(thinkingId, thinkingBuilder.toString()));
    }
  }

  private String answerText(ChatResponse response) {
    Generation generation = response.getResult();
    if (generation == null || generation.getOutput() == null) {
      return "";
    }
    String text = generation.getOutput().getText();
    return text == null ? "" : text;
  }

  private String thinkingText(ChatResponse response) {
    Generation generation = response.getResult();
    if (generation == null) {
      return "";
    }
    String messageThinking = generation.getOutput() == null ? "" : thinkingValue(generation.getOutput().getMetadata());
    if (!messageThinking.isEmpty()) {
      return messageThinking;
    }
    return thinkingValue(generation.getMetadata());
  }

  private String thinkingValue(ChatGenerationMetadata metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return "";
    }
    for (String key : metadata.keySet()) {
      if (isThinkingKey(key)) {
        return stringValue(metadata.get(key));
      }
    }
    for (Map.Entry<String, Object> entry : metadata.entrySet()) {
      String nested = thinkingValue(entry.getValue());
      if (!nested.isEmpty()) {
        return nested;
      }
    }
    return "";
  }

  private String thinkingValue(Map<String, Object> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return "";
    }
    for (Map.Entry<String, Object> entry : metadata.entrySet()) {
      if (isThinkingKey(entry.getKey())) {
        return stringValue(entry.getValue());
      }
    }
    for (Map.Entry<String, Object> entry : metadata.entrySet()) {
      String nested = thinkingValue(entry.getValue());
      if (!nested.isEmpty()) {
        return nested;
      }
    }
    return "";
  }

  @SuppressWarnings("unchecked")
  private String thinkingValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      return thinkingValue((Map<String, Object>) map);
    }
    return "";
  }

  private boolean isThinkingKey(String key) {
    if (key == null) {
      return false;
    }
    String normalized = key.toLowerCase(Locale.ROOT).replace("-", "_");
    return normalized.equals("thinking")
        || normalized.equals("reasoning")
        || normalized.equals("reasoning_content")
        || normalized.equals("reasoningcontent");
  }

  private String stringValue(Object value) {
    if (value == null) {
      return "";
    }
    return value.toString();
  }

  private String thinkingDelta(String current, AtomicReference<String> previousThinking) {
    String previous = previousThinking.get();
    previousThinking.set(current);
    if (current.startsWith(previous)) {
      return current.substring(previous.length());
    }
    return current;
  }

  private enum ChatRunStatus {
    SUCCESS,
    STAGNATED,
    LLM_CALL_BUDGET_EXCEEDED,
    REPLAN_BUDGET_EXCEEDED,
    OUTPUT_LIMIT,
    FAILED,
    CANCELLED
  }

  private record ChatRunResult(ChatRunStatus status, String text) {
    static ChatRunResult success(String text) {
      return new ChatRunResult(ChatRunStatus.SUCCESS, text);
    }

    static ChatRunResult outputLimit(String text) {
      return new ChatRunResult(ChatRunStatus.OUTPUT_LIMIT, text);
    }

    static ChatRunResult failed() {
      return new ChatRunResult(ChatRunStatus.FAILED, "");
    }

    static ChatRunResult cancelled() {
      return new ChatRunResult(ChatRunStatus.CANCELLED, "");
    }
  }

}
