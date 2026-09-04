package dev.mikoto2000.rei.summarize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;

import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.llm.ConversationIds;

class CurrentConversationHistoryAppenderTest {

  @Test
  void appendsSlashCommandConversationToChatMemoryAndConversationLogInOrder() {
    ChatMemory chatMemory = Mockito.mock(ChatMemory.class);
    ConversationLogStore logStore = Mockito.mock(ConversationLogStore.class);
    CurrentConversationHistoryAppender appender = new CurrentConversationHistoryAppender(
        chatMemory, Optional.of(logStore));

    appender.appendUserMessage("次のWebページを要約してください: https://example.com/article");
    appender.appendAssistantMessage("要約結果");

    ArgumentCaptor<List<Message>> messages = ArgumentCaptor.forClass(List.class);
    verify(chatMemory, Mockito.times(2)).add(eq(ConversationIds.chat()), messages.capture());
    assertEquals("次のWebページを要約してください: https://example.com/article",
        messages.getAllValues().get(0).getFirst().getText());
    assertEquals("要約結果", messages.getAllValues().get(1).getFirst().getText());
    var inOrder = Mockito.inOrder(logStore);
    inOrder.verify(logStore).append(ConversationIds.chat(), "user",
        "次のWebページを要約してください: https://example.com/article");
    inOrder.verify(logStore).append(ConversationIds.chat(), "assistant", "要約結果");
  }

  @Test
  void appendedMessagesAreAvailableToTheCurrentChatConversation() {
    MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(10).build();
    CurrentConversationHistoryAppender appender = new CurrentConversationHistoryAppender(
        chatMemory, Optional.empty());

    appender.appendUserMessage("次のWebページを要約してください: https://example.com/article");
    appender.appendAssistantMessage("要約結果");

    assertEquals(List.of("次のWebページを要約してください: https://example.com/article", "要約結果"),
        chatMemory.get(ConversationIds.chat()).stream().map(Message::getText).toList());
  }
}
