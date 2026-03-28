package dev.mikoto2000.rei.core.command;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
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
    ChatClientResponse chatClientResponse = chatClient
      .prompt(new Prompt(String.join(" ", prompts),
            OpenAiChatOptions.builder()
              .model(currentModelHolder.get())
              .build()))
      .call()
      .chatClientResponse();

    String thinking = chatClientResponse.chatResponse().getResult().getMetadata().get("thinking");
    if (thinking != null && !thinking.isBlank()) {
      IO.println(thinking);
    }
    String answer = chatClientResponse.chatResponse().getResult().getOutput().getText();
    IO.println(answer);

    IO.println("=== advisor context ===");
    List<Document> documents = (List<Document>)chatClientResponse.context().getOrDefault("qa_retrieved_documents", List.of());

    Set<String> documentNames = new HashSet<>();
    for (Document document : documents) {
      String documentName = document.getMetadata().get("source").toString();
      documentNames.add(documentName);
    }

    for (String documentName : documentNames) {
      IO.println("- " + documentName);
    }
  }
}

