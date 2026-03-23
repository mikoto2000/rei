package dev.mikoto2000.rei.core.command;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

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

  @Parameters(arity = "1..*", paramLabel = "PROMPT", description = "メッセージ")
  private String[] prompts;

  @Override
  public void run() {
    ChatResponse chatResponse = chatClient
      .prompt(String.join(" ", prompts))
      .call()
      .chatResponse();

    String thinking = chatResponse.getResult().getMetadata().get("thinking");
    IO.println(thinking);
    String answer = chatResponse.getResult().getOutput().getText();
    IO.println(answer);
  }
}

