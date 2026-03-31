package dev.mikoto2000.rei.core.command;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

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

  @Parameters(arity = "1..*", paramLabel = "PROMPT", description = "メッセージ")
  private String[] prompts;

  @Override
  public void run() {
    ChatClientRequestSpec requestSpec = chatClient
      .prompt(new Prompt(String.join(" ", prompts),
          OpenAiChatOptions.builder()
            .model(currentModelHolder.get())
            .build()));
    ChatClientResponse chatClientResponse = requestSpec
      .call()
      .chatClientResponse();

    String thinking = chatClientResponse.chatResponse().getResult().getMetadata().get("thinking");
    if (thinking != null && !thinking.isBlank()) {
      IO.println("=== thinking ===");
      IO.println(thinking);
    }

    IO.println("=== answer ===");
    String answer = chatClientResponse.chatResponse().getResult().getOutput().getText();
    IO.println(answer);
  }
}
