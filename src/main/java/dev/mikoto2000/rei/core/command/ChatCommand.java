package dev.mikoto2000.rei.core.command;

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
import java.util.concurrent.atomic.AtomicReference;

/**
 * ChatCommand
 */
@Command(
name = "chat",
description = "Chat with AI")
@RequiredArgsConstructor
public class ChatCommand implements Runnable {

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
      .doOnNext(System.out::print)
      .doOnComplete(latch::countDown)
      .doOnError(error -> {
        errorRef.set(error);
        latch.countDown();
      })
      .subscribe();
    cancellationService.register(disposable);

    try {
      latch.await();
      System.out.println();
      Throwable error = errorRef.get();
      if (error != null) {
        throw new IllegalStateException("回答の取得に失敗しました", error);
      }
    } catch (InterruptedException e) {
      if (cancellationService.consumeCancellationRequested()) {
        System.out.println();
        IO.println("[cancelled]");
        return;
      }
      Thread.currentThread().interrupt();
      throw new IllegalStateException("回答待機が中断されました", e);
    } finally {
      cancellationService.clear();
    }
  }
}
