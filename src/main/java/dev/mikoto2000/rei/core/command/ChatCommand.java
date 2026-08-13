package dev.mikoto2000.rei.core.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.agent.progress.AgentNoProgressException;
import dev.mikoto2000.rei.agent.progress.AgentProgressProperties;
import dev.mikoto2000.rei.agent.progress.AgentProgressSessionRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.llm.LlmChatClientProvider;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import dev.mikoto2000.rei.sound.ChatResponseNarrator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import reactor.core.Disposable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Locale;
import java.util.List;
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

  private final CommandCancellationService cancellationService;

  private final ChatResponseNarrator chatResponseNarrator;
  private final Optional<MemoryConsolidatorService> memoryConsolidatorService;
  private final AgentProgressSessionRegistry progressSessionRegistry;
  private final AgentProgressProperties progressProperties;
  private final InlineFileAttachmentResolver inlineFileAttachmentResolver = new InlineFileAttachmentResolver();

  public ChatCommand(ChatClient chatClient, ModelHolderService currentModelHolder,
      CommandCancellationService cancellationService, ChatResponseNarrator chatResponseNarrator,
      Optional<MemoryConsolidatorService> memoryConsolidatorService) {
    this(new FixedLlmChatClientProvider(chatClient), currentModelHolder, new FixedLlmModelProvider(),
        cancellationService, chatResponseNarrator, memoryConsolidatorService,
        new AgentProgressSessionRegistry(), new AgentProgressProperties());
  }

  @Autowired
  public ChatCommand(LlmChatClientProvider chatClientProvider, ModelHolderService currentModelHolder,
      LlmModelProvider modelProvider, CommandCancellationService cancellationService,
      ChatResponseNarrator chatResponseNarrator, Optional<MemoryConsolidatorService> memoryConsolidatorService,
      AgentProgressSessionRegistry progressSessionRegistry, AgentProgressProperties progressProperties) {
    this.chatClientProvider = chatClientProvider;
    this.currentModelHolder = currentModelHolder;
    this.modelProvider = modelProvider;
    this.cancellationService = cancellationService;
    this.chatResponseNarrator = chatResponseNarrator;
    this.memoryConsolidatorService = memoryConsolidatorService;
    this.progressSessionRegistry = progressSessionRegistry;
    this.progressProperties = progressProperties;
  }

  @Parameters(arity = "1..*", paramLabel = "PROMPT", description = "メッセージ")
  private String[] prompts;

  @Override
  public void run() {
    long startedAtNanos = System.nanoTime();
    cancellationService.begin(Thread.currentThread());
    chatResponseNarrator.reset();

    String promptText = String.join(" ", prompts);
    InlineFileAttachmentResolver.ResolvedPrompt resolvedPrompt = inlineFileAttachmentResolver.resolve(promptText);
    for (String warning : resolvedPrompt.warnings()) {
      IO.println(warning);
    }
    String progressSessionId = progressSessionRegistry.start(
        resolvedPrompt.prompt(),
        progressProperties.getMaxNoProgressIterations());

    ChatClientRequestSpec requestSpec = chatClientProvider.chatClient(LlmFeature.CHAT)
      .prompt(new Prompt(
          UserMessage.builder()
              .text(resolvedPrompt.prompt())
              .media(resolvedPrompt.media())
              .build(),
          OpenAiChatOptions.builder()
            .model(modelProvider.model(LlmFeature.CHAT, currentModelHolder.get()))
            .build()))
      .toolContext(Map.of(AgentProgressSessionRegistry.TOOL_CONTEXT_SESSION_ID, progressSessionId));

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> errorRef = new AtomicReference<>();
    AtomicBoolean headerPrinted = new AtomicBoolean(false);
    AtomicBoolean thinkingHeaderPrinted = new AtomicBoolean(false);
    AtomicReference<String> previousThinking = new AtomicReference<>("");
    StringBuilder responseBuilder = new StringBuilder();
    Disposable disposable;
    try {
      disposable = requestSpec.stream()
        .chatResponse()
        .subscribe(
            response -> {
              if (!headerPrinted.get()) {
                printThinking(response, thinkingHeaderPrinted, previousThinking);
              }
              String chunk = answerText(response);
              if (chunk == null || chunk.isEmpty()) {
                return;
              }
              if (headerPrinted.compareAndSet(false, true)) {
                if (thinkingHeaderPrinted.get()) {
                  System.out.println();
                }
                IO.println(answerHeader(startedAtNanos));
              }
              System.out.print(chunk);
              responseBuilder.append(chunk);
            },
            error -> {
              errorRef.set(error);
              latch.countDown();
            },
            latch::countDown);
    } catch (RuntimeException e) {
      log.warn("Chat response stream failed to start", e);
      System.err.println("[error] " + buildUserFacingMessage(e));
      cancellationService.clear();
      return;
    }
    cancellationService.register(disposable);

    try {
      latch.await();
      System.out.println();
      Throwable error = errorRef.get();
      if (error != null) {
        log.warn("Chat response failed", error);
        if (noProgressException(error) != null) {
          printNoProgressFinalAnswer(resolvedPrompt, startedAtNanos);
          return;
        }
        System.err.println("[error] " + buildUserFacingMessage(error));
        return;
      }
      chatResponseNarrator.narrateIfCompleted(responseBuilder.toString());
      maybeSuggestConsolidation();
    } catch (InterruptedException e) {
      if (cancellationService.consumeCancellationRequested()) {
        System.out.println();
        IO.println("[cancelled]");
        return;
      }
      Thread.currentThread().interrupt();
      log.warn("Chat response wait interrupted", e);
      IO.println("[error] 回答待機が中断されました");
    } finally {
      progressSessionRegistry.finish(progressSessionId);
      cancellationService.clear();
    }
  }

  private void printNoProgressFinalAnswer(InlineFileAttachmentResolver.ResolvedPrompt resolvedPrompt, long startedAtNanos) {
    try {
      String finalPrompt = resolvedPrompt.prompt()
          + """

              The agent has made no meaningful progress for several iterations.

              Do not call additional tools.
              Using the information already available:
              - summarize what has been established,
              - explain what remains unresolved,
              - explain why further progress could not be made,
              - provide the best possible answer to the user.
              """;
      String content = chatClientProvider.chatClient(LlmFeature.CHAT)
          .prompt(new Prompt(
              UserMessage.builder()
                  .text(finalPrompt)
                  .media(resolvedPrompt.media())
                  .build(),
              OpenAiChatOptions.builder()
                  .model(modelProvider.model(LlmFeature.CHAT, currentModelHolder.get()))
                  .build()))
          .toolCallbacks(List.of())
          .call()
          .content();
      IO.println(answerHeader(startedAtNanos));
      if (content != null && !content.isBlank()) {
        IO.println(content);
      }
      chatResponseNarrator.narrateIfCompleted(content == null ? "" : content);
    } catch (RuntimeException e) {
      log.warn("No-progress final answer generation failed", e);
      System.err.println("[error] " + buildUserFacingMessage(e));
    }
  }

  private AgentNoProgressException noProgressException(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof AgentNoProgressException noProgressException) {
        return noProgressException;
      }
      for (Throwable suppressed : current.getSuppressed()) {
        AgentNoProgressException suppressedNoProgress = noProgressException(suppressed);
        if (suppressedNoProgress != null) {
          return suppressedNoProgress;
        }
      }
      current = current.getCause();
    }
    return null;
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

  private void printThinking(ChatResponse response, AtomicBoolean thinkingHeaderPrinted,
      AtomicReference<String> previousThinking) {
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

  public static class FixedLlmChatClientProvider extends LlmChatClientProvider {
    private final ChatClient chatClient;

    public FixedLlmChatClientProvider(ChatClient chatClient) {
      super(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
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
