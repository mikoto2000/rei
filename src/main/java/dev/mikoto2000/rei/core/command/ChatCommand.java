package dev.mikoto2000.rei.core.command;

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
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.event.ErrorInformation;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.llm.ConversationIds;
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
import dev.mikoto2000.rei.sound.ChatResponseNarrator;
import dev.mikoto2000.rei.skills.AgentSkillAdvisor;
import dev.mikoto2000.rei.skills.SkillRoutingRunContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
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

/**
 * ChatCommand
 */
@Command(
name = "chat",
description = "Chat with AI")
@Component
public class ChatCommand implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(ChatCommand.class);

  private final LlmChatClientProvider chatClientProvider;

  private final ModelHolderService currentModelHolder;
  private final LlmModelProvider modelProvider;
  private final LlmProperties llmProperties;

  private final CommandCancellationService cancellationService;

  private final ChatResponseNarrator chatResponseNarrator;
  private final Optional<MemoryConsolidatorService> memoryConsolidatorService;
  private final Optional<OutputLimitReplanner> outputLimitReplanner;
  private final AgentEventFactory eventFactory;
  private final AgentEventPublisher eventPublisher;
  private final InlineFileAttachmentResolver inlineFileAttachmentResolver = new InlineFileAttachmentResolver();
  private ConversationLogStore conversationLogStore;

  @Autowired
  void setConversationLogStore(ConversationLogStore conversationLogStore) {
    this.conversationLogStore = conversationLogStore;
  }

  public ChatCommand(ChatClient chatClient, ModelHolderService currentModelHolder,
      CommandCancellationService cancellationService, ChatResponseNarrator chatResponseNarrator,
      Optional<MemoryConsolidatorService> memoryConsolidatorService) {
    this(new FixedLlmChatClientProvider(chatClient), currentModelHolder, new FixedLlmModelProvider(),
        new LlmProperties(), cancellationService, chatResponseNarrator, memoryConsolidatorService, Optional.empty(),
        new AgentEventFactory(Clock.systemDefaultZone()), new InMemoryAgentEventBus());
  }

  public ChatCommand(ChatClient chatClient, ModelHolderService currentModelHolder,
      CommandCancellationService cancellationService, ChatResponseNarrator chatResponseNarrator,
      Optional<MemoryConsolidatorService> memoryConsolidatorService, AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    this(new FixedLlmChatClientProvider(chatClient), currentModelHolder, new FixedLlmModelProvider(),
        new LlmProperties(), cancellationService, chatResponseNarrator, memoryConsolidatorService, Optional.empty(),
        eventFactory, eventPublisher);
  }

  public ChatCommand(LlmChatClientProvider chatClientProvider, ModelHolderService currentModelHolder,
      LlmModelProvider modelProvider, LlmProperties llmProperties, CommandCancellationService cancellationService,
      ChatResponseNarrator chatResponseNarrator, Optional<MemoryConsolidatorService> memoryConsolidatorService,
      Optional<OutputLimitReplanner> outputLimitReplanner) {
    this(chatClientProvider, currentModelHolder, modelProvider, llmProperties, cancellationService,
        chatResponseNarrator, memoryConsolidatorService, outputLimitReplanner,
        new AgentEventFactory(Clock.systemDefaultZone()), new InMemoryAgentEventBus());
  }

  @Autowired
  public ChatCommand(LlmChatClientProvider chatClientProvider, ModelHolderService currentModelHolder,
      LlmModelProvider modelProvider, LlmProperties llmProperties, CommandCancellationService cancellationService,
      ChatResponseNarrator chatResponseNarrator, Optional<MemoryConsolidatorService> memoryConsolidatorService,
      Optional<OutputLimitReplanner> outputLimitReplanner, AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    this.chatClientProvider = chatClientProvider;
    this.currentModelHolder = currentModelHolder;
    this.modelProvider = modelProvider;
    this.llmProperties = llmProperties;
    this.cancellationService = cancellationService;
    this.chatResponseNarrator = chatResponseNarrator;
    this.memoryConsolidatorService = memoryConsolidatorService;
    this.outputLimitReplanner = outputLimitReplanner;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
  }

  @Parameters(arity = "1..*", paramLabel = "PROMPT", description = "メッセージ")
  private String[] prompts;

  @Override
  public void run() {
    long startedAtNanos = System.nanoTime();
    cancellationService.begin(Thread.currentThread());
    chatResponseNarrator.reset();
    String runId = UUID.randomUUID().toString();
    SkillRoutingRunContext skillRoutingContext = new SkillRoutingRunContext(runId);
    AtomicLong runCompletionTokens = new AtomicLong();
    AtomicBoolean usageAvailable = new AtomicBoolean();
    AtomicReference<GenerationMetrics> lastGenerationMetrics = new AtomicReference<>();

    try {
      String promptText = String.join(" ", prompts);
      appendConversationLog("user", promptText);
      OutputLimitRunBudget budget = new OutputLimitRunBudget(
          llmProperties.getOutputLimit().getMaxReplansPerGoal(),
          llmProperties.getOutputLimit().getMaxLlmCallsPerRun());
      if (!budget.tryConsumeLlmCall()) {
        log.warn("Chat skipped: LLM call budget exhausted before initial prompt");
        System.err.println("[error] LLM call budget exhausted before initial prompt");
        return;
      }
      eventPublisher.publish(eventFactory.runStarted(runId, "user-request", null));
      ChatRunResult result = executePrompt(promptText, true, startedAtNanos, budget, runId, skillRoutingContext,
          runCompletionTokens, usageAvailable, lastGenerationMetrics);
      if (result.status() == ChatRunStatus.OUTPUT_LIMIT) {
        result = handleOutputLimit(promptText, promptText, "", result.text(), budget, startedAtNanos, runId,
            skillRoutingContext,
            runCompletionTokens, usageAvailable, lastGenerationMetrics);
      }
      if (result.status() == ChatRunStatus.SUCCESS) {
        appendConversationLog("assistant", result.text());
        GenerationMetrics metrics = lastGenerationMetrics.get();
        eventPublisher.publish(eventFactory.runCompleted(runId, elapsedMillis(startedAtNanos),
            usageAvailable.get() ? runCompletionTokens.get() : null,
            metrics == null ? null : metrics.timeToFirstTokenMillis(),
            metrics == null ? null : metrics.outputTokensPerSecond(),
            metrics == null ? null : metrics.endToEndTokensPerSecond()));
        chatResponseNarrator.narrateIfCompleted(result.text());
        maybeSuggestConsolidation();
      } else {
        eventPublisher.publish(eventFactory.runFailed(runId, terminalError(result.status())));
      }
    } finally {
      cancellationService.clear();
    }
  }

  private void appendConversationLog(String speaker, String content) {
    if (conversationLogStore != null) {
      conversationLogStore.append(ConversationIds.chat(), speaker, content);
    }
  }

  private ErrorInformation terminalError(ChatRunStatus status) {
    return switch (status) {
      case OUTPUT_LIMIT -> new ErrorInformation("OutputLimit", "output token limit reached", "output_limit");
      case CANCELLED -> new ErrorInformation("Cancelled", "chat run cancelled", "cancelled");
      case FAILED -> new ErrorInformation("ChatRunFailed", "chat run failed", null);
      case SUCCESS -> throw new IllegalArgumentException("SUCCESS is not a failed terminal state");
    };
  }

  private ChatRunResult handleOutputLimit(String originalUserRequest, String currentGoal, String progressSoFar,
      String partialOutput, OutputLimitRunBudget budget, long startedAtNanos, String runId,
      SkillRoutingRunContext skillRoutingContext,
      AtomicLong runCompletionTokens, AtomicBoolean usageAvailable,
      AtomicReference<GenerationMetrics> lastGenerationMetrics) {
    if (outputLimitReplanner.isEmpty()) {
      System.err.println("[error] output token limit reached");
      return ChatRunResult.outputLimit(partialOutput);
    }
    if (!budget.hasRemainingLlmCalls()) {
      log.warn("Output limit replan skipped: goal={}, reason=llm_call_budget_exhausted_before_planner",
          summarizeForLog(currentGoal));
      System.err.println("[error] output token limit reached and LLM call budget exhausted");
      return ChatRunResult.outputLimit(partialOutput);
    }
    if (!budget.tryConsumeReplan()) {
      log.warn("Output limit replan skipped: goal={}, reason=replan_budget_exhausted, replanCount={}",
          summarizeForLog(currentGoal), budget.replanCount());
      System.err.println("[error] output token limit reached and replan budget exhausted");
      return ChatRunResult.outputLimit(partialOutput);
    }
    if (!budget.tryConsumeLlmCall()) {
      log.warn("Output limit replan skipped: goal={}, reason=llm_call_budget_exhausted_before_planner",
          summarizeForLog(currentGoal));
      System.err.println("[error] output token limit reached and LLM call budget exhausted");
      return ChatRunResult.outputLimit(partialOutput);
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
      System.err.println("[error] output token limit reached and replan failed: " + e.getMessage());
      return ChatRunResult.outputLimit(partialOutput);
    }

    StringBuilder subgoalResults = new StringBuilder();
    for (OutputLimitReplanSubgoal subgoal : plan.subgoals()) {
      if (!budget.tryConsumeLlmCall()) {
        log.warn("Output limit subgoal skipped: LLM call budget exhausted");
        System.err.println("[error] LLM call budget exhausted before subgoal: " + subgoal.id());
        return ChatRunResult.outputLimit(subgoalResults.toString());
      }
      log.info("Output limit subgoal started: id={}, goal={}", subgoal.id(), subgoal.goal());
      ChatRunResult subgoalResult = executePrompt(subgoal.goal(), false, startedAtNanos, budget, runId,
          skillRoutingContext,
           runCompletionTokens, usageAvailable, lastGenerationMetrics);
      log.info("Output limit subgoal finished: id={}, status={}", subgoal.id(), subgoalResult.status());
      if (subgoalResult.status() == ChatRunStatus.OUTPUT_LIMIT) {
        subgoalResult = handleOutputLimit(originalUserRequest, subgoal.goal(), subgoalResults.toString(),
            subgoalResult.text(), budget, startedAtNanos, runId, skillRoutingContext, runCompletionTokens, usageAvailable,
             lastGenerationMetrics);
      }
      if (subgoalResult.status() != ChatRunStatus.SUCCESS) {
        return subgoalResult;
      }
      subgoalResults.append("## ").append(subgoal.id()).append("\n")
          .append(subgoalResult.text()).append("\n\n");
    }

    if (!budget.tryConsumeLlmCall()) {
      log.warn("Output limit final integration skipped: LLM call budget exhausted");
      System.err.println("[error] LLM call budget exhausted before final integration");
      return ChatRunResult.outputLimit(subgoalResults.toString());
    }
    return executePrompt(buildIntegrationPrompt(originalUserRequest, plan.finalGoal(), subgoalResults.toString()),
        false, startedAtNanos, budget, runId, skillRoutingContext, runCompletionTokens, usageAvailable,
        lastGenerationMetrics);
  }

  private ChatRunResult executePrompt(String promptText, boolean resolveAttachments, long startedAtNanos,
      OutputLimitRunBudget budget, String runId, SkillRoutingRunContext skillRoutingContext,
      AtomicLong runCompletionTokens, AtomicBoolean usageAvailable,
      AtomicReference<GenerationMetrics> lastGenerationMetrics) {
    InlineFileAttachmentResolver.ResolvedPrompt resolvedPrompt = resolveAttachments
        ? inlineFileAttachmentResolver.resolve(promptText)
        : new InlineFileAttachmentResolver.ResolvedPrompt(promptText, java.util.List.of(), java.util.List.of());
    for (String warning : resolvedPrompt.warnings()) {
      IO.println(warning);
    }

    ChatClientRequestSpec requestSpec = chatClientProvider.chatClient(LlmFeature.CHAT)
      .prompt(new Prompt(
          UserMessage.builder()
              .text(resolvedPrompt.prompt())
              .media(resolvedPrompt.media())
              .build(),
          modelProvider.chatOptions(LlmFeature.CHAT, currentModelHolder.get(), true)))
      .advisors(advisor -> advisor
          .param(ChatMemory.CONVERSATION_ID, ConversationIds.chat())
          .param(AgentSkillAdvisor.ROUTING_CONTEXT_KEY, skillRoutingContext));

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> errorRef = new AtomicReference<>();
    AtomicBoolean headerPrinted = new AtomicBoolean(false);
    AtomicBoolean thinkingHeaderPrinted = new AtomicBoolean(false);
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
    try {
      disposable = requestSpec.stream()
        .chatResponse()
        .subscribe(
            response -> {
              if (OutputLimitDetector.isOutputLimitReached(response)) {
                outputLimitReached.set(true);
              }
              captureCompletionTokens(response, completionTokens);
              if (!headerPrinted.get()) {
                printThinking(response, thinkingHeaderPrinted, previousThinking, thinkingEventStarted,
                    thinkingId, thinkingBuilder);
              }
              String chunk = answerText(response);
              if (chunk == null || chunk.isEmpty()) {
                return;
              }
              if (messageStarted.compareAndSet(false, true)) {
                completeThinking(thinkingEventStarted, thinkingEventCompleted, thinkingId, thinkingBuilder);
                eventPublisher.publish(eventFactory.messageStarted(messageId, "assistant"));
              }
              if (headerPrinted.compareAndSet(false, true)) {
                if (thinkingHeaderPrinted.get()) {
                  System.out.println();
                }
                IO.println(answerHeader(startedAtNanos));
                answerStartedAtNanos.compareAndSet(0L, System.nanoTime());
              }
              answerLastChunkAtNanos.set(System.nanoTime());
              answerChunkCount.incrementAndGet();
              System.out.print(chunk);
              responseBuilder.append(chunk);
              eventPublisher.publish(eventFactory.messageDelta(messageId, chunk));
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
      System.err.println("[error] " + buildUserFacingMessage(e));
      return ChatRunResult.failed();
    }
    cancellationService.register(disposable);

    try {
      latch.await();
      if (completionTokens.get() > 0) {
        runCompletionTokens.addAndGet(completionTokens.get());
        usageAvailable.set(true);
      }
      completeThinking(thinkingEventStarted, thinkingEventCompleted, thinkingId, thinkingBuilder);
      System.out.println();
      Throwable error = errorRef.get();
      if (error != null) {
        log.warn("Chat response failed", error);
        System.err.println("[error] " + buildUserFacingMessage(error));
        return ChatRunResult.failed();
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
      printGenerationMetrics(metrics);
      return ChatRunResult.success(responseBuilder.toString());
    } catch (InterruptedException e) {
      completeThinking(thinkingEventStarted, thinkingEventCompleted, thinkingId, thinkingBuilder);
      if (cancellationService.consumeCancellationRequested()) {
        System.out.println();
        IO.println("[cancelled]");
        return ChatRunResult.cancelled();
      }
      Thread.currentThread().interrupt();
      log.warn("Chat response wait interrupted", e);
      IO.println("[error] 回答待機が中断されました");
      return ChatRunResult.failed();
    }
  }

  private long elapsedMillis(long startedAtNanos) {
    return (System.nanoTime() - startedAtNanos) / 1_000_000L;
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

  private void maybeSuggestConsolidation() {
    memoryConsolidatorService.ifPresent(service -> {
      try {
        if (service.shouldSuggestConsolidationNow()) {
          IO.println("[memory] 記憶整理を実行することをお勧めします。/memory consolidate を実行してください。");
        }
      } catch (Exception ignored) {
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

  String answerHeader(long startedAtNanos) {
    double elapsedSeconds = (System.nanoTime() - startedAtNanos) / 1_000_000_000.0d;
    return "=== answer(" + String.format(Locale.ROOT, "%.1f", elapsedSeconds) + " s) ===";
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

  static GenerationMetrics calculateGenerationMetrics(long requestStartedAtNanos, long answerStartedAtNanos,
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

  private void printGenerationMetrics(GenerationMetrics metrics) {
    if (metrics == null) return;
    String outputSpeed = metrics.outputTokensPerSecond() == null
        ? "unavailable" : String.format(Locale.ROOT, "%.1f tok/s", metrics.outputTokensPerSecond());
    IO.println("=== metrics(TTFT " + String.format(Locale.ROOT, "%.1f ms", metrics.timeToFirstTokenMillis())
        + ", output " + outputSpeed + ", end-to-end "
        + String.format(Locale.ROOT, "%.1f tok/s", metrics.endToEndTokensPerSecond()) + ") ===");
  }

  record GenerationMetrics(Double timeToFirstTokenMillis, Double outputTokensPerSecond,
      Double endToEndTokensPerSecond) { }

  private void printThinking(ChatResponse response, AtomicBoolean thinkingHeaderPrinted,
      AtomicReference<String> previousThinking, AtomicBoolean thinkingEventStarted,
      String thinkingId, StringBuilder thinkingBuilder) {
    String thinking = thinkingText(response);
    if (thinking == null || thinking.isEmpty()) {
      return;
    }
    String delta = thinkingDelta(thinking, previousThinking);
    if (delta.isEmpty()) {
      return;
    }
    if (thinkingHeaderPrinted.compareAndSet(false, true)) {
      IO.println("=== thinking ===");
    }
    System.out.print(delta);
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

  public static class FixedLlmChatClientProvider extends LlmChatClientProvider {
    private final ChatClient chatClient;

    public FixedLlmChatClientProvider(ChatClient chatClient) {
      super(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
          null, null, null, null, null);
      this.chatClient = chatClient;
    }

    @Override
    public ChatClient chatClient(String feature) {
      return chatClient;
    }
  }

  public static class FixedLlmModelProvider extends LlmModelProvider {
    public FixedLlmModelProvider() {
      super(null, new dev.mikoto2000.rei.llm.LlmProperties());
    }

    @Override
    public String model(String feature, String defaultModel) {
      return defaultModel;
    }
  }
}
