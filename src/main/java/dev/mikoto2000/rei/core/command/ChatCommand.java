package dev.mikoto2000.rei.core.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import reactor.core.Disposable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

  @Parameters(arity = "1..*", paramLabel = "PROMPT", description = "メッセージ")
  private String[] prompts;

  @Override
  public void run() {
    cancellationService.begin(Thread.currentThread());
    ChatClientRequestSpec requestSpec = chatClient
      .prompt(new Prompt(String.join(" ", prompts),
          OpenAiChatOptions.builder()
            .model(currentModelHolder.get())
            .build()));

    IO.println("=== answer ===");
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> errorRef = new AtomicReference<>();
    Disposable disposable = requestSpec.stream()
      .content()
      .subscribe(
          System.out::print,
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
      }
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
}
