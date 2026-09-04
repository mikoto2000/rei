package dev.mikoto2000.rei.summarize;

import java.util.List;
import java.util.Optional;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.llm.ConversationIds;

@Component
public class CurrentConversationHistoryAppender implements ConversationHistoryAppender {

  private final ChatMemory chatMemory;
  private final Optional<ConversationLogStore> conversationLogStore;

  public CurrentConversationHistoryAppender(ChatMemory chatMemory, Optional<ConversationLogStore> conversationLogStore) {
    this.chatMemory = chatMemory;
    this.conversationLogStore = conversationLogStore;
  }

  @Override
  public void appendUserMessage(String content) {
    chatMemory.add(ConversationIds.chat(), List.of(new UserMessage(content)));
    appendLog("user", content);
  }

  @Override
  public void appendAssistantMessage(String content) {
    chatMemory.add(ConversationIds.chat(), List.of(new AssistantMessage(content)));
    appendLog("assistant", content);
  }

  private void appendLog(String speaker, String content) {
    conversationLogStore.ifPresent(store -> store.append(ConversationIds.chat(), speaker, content));
  }
}
