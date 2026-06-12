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

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.sound.ChatResponseNarrator;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import reactor.core.Disposable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
@RequiredArgsConstructor
public class ChatCommand implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(ChatCommand.class);

  private final ChatClient chatClient;

  private final ModelHolderService currentModelHolder;

  private final CommandCancellationService cancellationService;

  private final ChatResponseNarrator chatResponseNarrator;
  private final Optional<MemoryConsolidatorService> memoryConsolidatorService;
  private final InlineFileAttachmentResolver inlineFileAttachmentResolver = new InlineFileAttachmentResolver();

  @Parameters(arity = "1..*", paramLabel = "PROMPT", description = "メッセージ")
  private String[] prompts;

  @Override
  public void run() {
    long startedAtNanos = System.nanoTime();
    cancellationService.begin(Thread.currentThread());
    chatResponseNarrator.reset();

    InlineFileAttachmentResolver.ResolvedPrompt resolvedPrompt = inlineFileAttachmentResolver.resolve(String.join(" ", prompts));
    for (String warning : resolvedPrompt.warnings()) {
      IO.println(warning);
    }

    ChatClientRequestSpec requestSpec = chatClient
      .prompt(new Prompt(
          UserMessage.builder()
              .text(resolvedPrompt.prompt())
              .media(resolvedPrompt.media())
              .build(),
          OpenAiChatOptions.builder()
            .model(currentModelHolder.get())
            .build()));

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
      boolean completed = latch.await(streamTimeoutMillis(), TimeUnit.MILLISECONDS);
      if (!completed) {
        disposable.dispose();
        log.warn("Chat response timed out after {} ms", streamTimeoutMillis());
        System.out.println();
        IO.println("[error] 回答の取得がタイムアウトしました");
        return;
      }
      System.out.println();
      Throwable error = errorRef.get();
      if (error != null) {
        log.warn("Chat response failed", error);
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
      cancellationService.clear();
    }
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

  long streamTimeoutMillis() {
    return 1_800_000L;
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
}
