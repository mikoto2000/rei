package dev.mikoto2000.rei.command;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

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

  private final ChatModel chatModel;

  @Parameters(arity = "1..*", paramLabel = "PROMPT", description = "メッセージ")
  private String[] prompts;

  @Override
  public void run() {
    ChatResponse chatResponse = chatModel.call(new Prompt(String.join(" ", prompts)));

    String thinking = chatResponse.getResult().getMetadata().get("thinking");
    IO.println(thinking);
    String answer = chatResponse.getResult().getOutput().getText();
    IO.println(answer);
  }
}

