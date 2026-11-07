package dev.mikoto2000.rei.summarize;

import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.llm.ConversationIds;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/** 情報の要約に必要な現在会話履歴Appenderを扱う。 */
@Component
public class CurrentConversationHistoryAppender implements ConversationHistoryAppender {

  /** チャット記憶。 */
  private final BiConsumer<String, Message> addToMemory;

  /** 会話ログ保存先。 */
  private final Optional<ConversationLogStore> conversationLogStore;

  /** 指定された値や利用先を使って、CurrentConversationHistoryAppenderを初期化する。 */
  public CurrentConversationHistoryAppender(
      ChatMemory chatMemory, Optional<ConversationLogStore> conversationLogStore) {
    this.addToMemory = (id, message) -> chatMemory.add(id, List.of(message));
    this.conversationLogStore = conversationLogStore;
  }

  /** 既存の記録へ経緯を残すため、利用者メッセージを追記する。 */
  @Override
  public void appendUserMessage(String content) {
    if (nonConversationalWebRun()) return;
    addToMemory.accept(ConversationIds.currentChat(), new UserMessage(content));
    appendLog("user", content);
  }

  /** 既存の記録へ経緯を残すため、アシスタントメッセージを追記する。 */
  @Override
  public void appendAssistantMessage(String content) {
    if (nonConversationalWebRun()) return;
    addToMemory.accept(ConversationIds.currentChat(), new AssistantMessage(content));
    appendLog("assistant", content);
  }

  /** 既存の記録へ経緯を残すため、診断用のログ出力先を追記する。 */
  private void appendLog(String speaker, String content) {
    conversationLogStore.ifPresent(
        store -> store.append(ConversationIds.currentChat(), speaker, content));
  }

  /** 情報の要約において、以外ConversationalWeb実行に該当するか判定する。 */
  private boolean nonConversationalWebRun() {
    var run = dev.mikoto2000.rei.core.chat.AgentRunScope.current();
    return run != null
        && run.requestSource() == dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.WEB
        && run.conversationId().isEmpty();
  }
}
