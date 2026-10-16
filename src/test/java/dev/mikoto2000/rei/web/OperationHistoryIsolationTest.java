package dev.mikoto2000.rei.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.summarize.CurrentConversationHistoryAppender;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;

class OperationHistoryIsolationTest {
  @Test
  void nonConversationalWebSummaryDoesNotWriteSharedChatHistory() {
    var memory = mock(ChatMemory.class);
    var log = mock(ConversationLogStore.class);
    var appender = new CurrentConversationHistoryAppender(memory, Optional.of(log));
    try (var scope =
        AgentRunScope.open(
            new AgentRunContext(
                "run", "", Path.of("."), "project", AgentRunContext.RequestSource.WEB))) {
      appender.appendUserMessage("url");
      appender.appendAssistantMessage("summary");
    }
    verifyNoInteractions(memory, log);
  }
}
