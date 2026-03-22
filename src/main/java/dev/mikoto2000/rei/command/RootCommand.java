package dev.mikoto2000.rei.command;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * RootCommand
 */
@Component
@Command(
name = "",
description = "AI shell")
@RequiredArgsConstructor
public class RootCommand implements Runnable {

  private final ChatModel chatModel;

  @Parameters(arity = "1..*", paramLabel = "PROMPT", description = "メッセージ")
  private String[] prompts;

  @Override
  public void run() {
    ChatResponse chatResponse = chatModel.call(new Prompt("こんにちは！"));

    String thinking = chatResponse.getResult().getMetadata().get("thinking");
    IO.println(thinking);
    String answer = chatResponse.getResult().getOutput().getText();
    IO.println(answer);
  }
}
