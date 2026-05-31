package dev.mikoto2000.rei.core.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.messages.UserMessage;
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
    StringBuilder responseBuilder = new StringBuilder();
    Disposable disposable = requestSpec.stream()
      .content()
      .subscribe(
          chunk -> {
            if (headerPrinted.compareAndSet(false, true)) {
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
        IO.println("[error] " + buildUserFacingMessage(error));
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
}
