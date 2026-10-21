package dev.mikoto2000.rei.llm;

import org.springframework.ai.chat.client.ChatClient;

/** 固定言語モデルモデルへ対話を依頼する窓口を用途や設定に応じて提供する窓口。 */
public class FixedLlmChatClientProvider extends LlmChatClientProvider {
  /** モデルへ対話を依頼する窓口。 */
  private final ChatClient chatClient;

  /** 指定された値や利用先を使って、FixedLlmChatClientProviderを初期化する。 */
  public FixedLlmChatClientProvider(ChatClient chatClient) {
    super(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null);
    this.chatClient = chatClient;
  }

  /** 用途に応じたモデル利用で用いるモデルへ対話を依頼する窓口を返す。 */
  @Override
  public ChatClient chatClient(String feature) {
    return chatClient;
  }
}
