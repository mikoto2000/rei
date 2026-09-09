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
  @Test void backgroundSummaryWritesCapturedConversationAfterShellSwitch() throws Exception {
    var memory=MessageWindowChatMemory.builder().maxMessages(10).build();
    var logs=Mockito.mock(ConversationLogStore.class);
    var appender=new CurrentConversationHistoryAppender(memory,Optional.of(logs));
    var id=java.util.UUID.randomUUID().toString();
    String conversation="project:"+id+":chat:main";
    var execution=new dev.mikoto2000.rei.core.execution.ActiveExecution("summary",id,conversation,java.nio.file.Path.of("A"),
        dev.mikoto2000.rei.core.execution.ExecutionType.SUMMARIZE,"source",java.time.Instant.now());
    try(var scope=dev.mikoto2000.rei.core.execution.ExecutionScope.open(execution)) {
      appender.appendUserMessage("request"); appender.appendAssistantMessage("summary A");
    }
    assertEquals(List.of("request","summary A"),memory.get(conversation).stream().map(Message::getText).toList());
    verify(logs).append(conversation,"assistant","summary A");
  }

  @Test
  void appendsSlashCommandConversationToChatMemoryAndConversationLogInOrder() {
    ChatMemory chatMemory = Mockito.mock(ChatMemory.class);
    ConversationLogStore logStore = Mockito.mock(ConversationLogStore.class);
    CurrentConversationHistoryAppender appender = new CurrentConversationHistoryAppender(
        chatMemory, Optional.of(logStore));

    appender.appendUserMessage("次のWebページを要約してください: https://example.com/article");
    appender.appendAssistantMessage("要約結果");

    ArgumentCaptor<List<Message>> messages = ArgumentCaptor.forClass(List.class);
    verify(chatMemory, Mockito.times(2)).add(eq(ConversationIds.currentChat()), messages.capture());
    assertEquals("次のWebページを要約してください: https://example.com/article",
        messages.getAllValues().get(0).getFirst().getText());
    assertEquals("要約結果", messages.getAllValues().get(1).getFirst().getText());
    var inOrder = Mockito.inOrder(logStore);
    inOrder.verify(logStore).append(ConversationIds.currentChat(), "user",
        "次のWebページを要約してください: https://example.com/article");
    inOrder.verify(logStore).append(ConversationIds.currentChat(), "assistant", "要約結果");
  }

  @Test
  void appendedMessagesAreAvailableToTheCurrentChatConversation() {
    MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(10).build();
    CurrentConversationHistoryAppender appender = new CurrentConversationHistoryAppender(
        chatMemory, Optional.empty());

    appender.appendUserMessage("次のWebページを要約してください: https://example.com/article");
    appender.appendAssistantMessage("要約結果");

    assertEquals(List.of("次のWebページを要約してください: https://example.com/article", "要約結果"),
        chatMemory.get(ConversationIds.currentChat()).stream().map(Message::getText).toList());
  }
}
