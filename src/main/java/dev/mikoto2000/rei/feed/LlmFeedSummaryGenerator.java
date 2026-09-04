package dev.mikoto2000.rei.feed;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.configuration.CoreProperties;
import dev.mikoto2000.rei.llm.ConversationIds;
import dev.mikoto2000.rei.llm.LlmChatClientProvider;
import dev.mikoto2000.rei.llm.LlmFeature;

@Component
public class LlmFeedSummaryGenerator implements FeedSummaryGenerator {

  private final LlmChatClientProvider chatClientProvider;

  public LlmFeedSummaryGenerator(ChatModel chatModel, CoreProperties coreProperties) {
    this(new dev.mikoto2000.rei.llm.FixedLlmChatClientProvider(
        org.springframework.ai.chat.client.ChatClient.builder(chatModel)
            .defaultSystem(coreProperties.systemPrompt())
            .build()));
  }

  @Autowired
  public LlmFeedSummaryGenerator(LlmChatClientProvider chatClientProvider) {
    this.chatClientProvider = chatClientProvider;
  }

  @Override
  public String generate(String prompt) {
    return chatClientProvider.chatClient(LlmFeature.FEED_SUMMARY).prompt()
        .user(prompt)
        .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, ConversationIds.tool("feed-summary")))
        .call()
        .content();
  }
}
