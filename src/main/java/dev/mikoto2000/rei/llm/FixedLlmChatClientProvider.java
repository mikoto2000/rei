package dev.mikoto2000.rei.llm;

import org.springframework.ai.chat.client.ChatClient;

public class FixedLlmChatClientProvider extends LlmChatClientProvider {
  private final ChatClient chatClient;

  public FixedLlmChatClientProvider(ChatClient chatClient) {
    super(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null);
    this.chatClient = chatClient;
  }

  @Override
  public ChatClient chatClient(String feature) {
    return chatClient;
  }
}
