package dev.mikoto2000.rei.feed;

import dev.mikoto2000.rei.core.configuration.CoreProperties;
import dev.mikoto2000.rei.llm.ConversationIds;
import dev.mikoto2000.rei.llm.LlmChatClientProvider;
import dev.mikoto2000.rei.llm.LlmFeature;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 言語モデルフィード要約の内容を生成する。 */
@Component
public class LlmFeedSummaryGenerator implements FeedSummaryGenerator {

  /** モデルへ対話を依頼する窓口提供元。 */
  private final LlmChatClientProvider chatClientProvider;

  /** フィードの購読と収集を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public LlmFeedSummaryGenerator(ChatModel chatModel, CoreProperties coreProperties) {
    this(
        new dev.mikoto2000.rei.llm.FixedLlmChatClientProvider(
            org.springframework.ai.chat.client.ChatClient.builder(chatModel)
                .defaultSystem(coreProperties.systemPrompt())
                .build()));
  }

  /** 指定された値や利用先を使って、LlmFeedSummaryGeneratorを初期化する。 */
  @Autowired
  public LlmFeedSummaryGenerator(LlmChatClientProvider chatClientProvider) {
    this.chatClientProvider = chatClientProvider;
  }

  /** 指定された条件に従って、依頼された内容を生成する。 */
  @Override
  public String generate(String prompt) {
    return chatClientProvider
        .chatClient(LlmFeature.FEED_SUMMARY)
        .prompt()
        .user(prompt)
        .advisors(
            advisor ->
                advisor.param(ChatMemory.CONVERSATION_ID, ConversationIds.tool("feed-summary")))
        .call()
        .content();
  }
}
