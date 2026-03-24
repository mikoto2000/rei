package dev.mikoto2000.rei.core.command;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;

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
    ChatClientResponse chatClientResponse = chatClient
      .prompt(String.join(" ", prompts))
      .call()
      .chatClientResponse();

    String thinking = chatClientResponse.chatResponse().getResult().getMetadata().get("thinking");
    IO.println(thinking);
    String answer = chatClientResponse.chatResponse().getResult().getOutput().getText();
    IO.println(answer);

    //IO.println("=== advisor context ===");
    //chatClientResponse.context().forEach((k, v) -> {
    //    IO.println(k + " = " + v);
    //});
  }
}

