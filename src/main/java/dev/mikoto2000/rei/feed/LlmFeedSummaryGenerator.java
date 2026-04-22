package dev.mikoto2000.rei.feed;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.configuration.CoreProperties;
import lombok.RequiredArgsConstructor;

@Component
public class LlmFeedSummaryGenerator implements FeedSummaryGenerator {

  private final ChatClient chatClient;

  public LlmFeedSummaryGenerator(ChatModel chatModel, CoreProperties coreProperties) {
    this.chatClient = ChatClient.builder(chatModel)
        .defaultSystem(coreProperties.systemPrompt())
        .build();
  }

  @Override
  public String generate(String prompt) {
    return chatClient.prompt()
        .user(prompt)
        .call()
        .content();
  }
}
