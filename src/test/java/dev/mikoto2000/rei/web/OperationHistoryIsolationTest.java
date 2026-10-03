package dev.mikoto2000.rei.web;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.summarize.CurrentConversationHistoryAppender;
import dev.mikoto2000.rei.conversation.ConversationLogStore;
import org.springframework.ai.chat.memory.ChatMemory;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.nio.file.Path;
import static org.mockito.Mockito.*;
class OperationHistoryIsolationTest {
  @Test void nonConversationalWebSummaryDoesNotWriteSharedChatHistory() {
    var memory=mock(ChatMemory.class); var log=mock(ConversationLogStore.class);
    var appender=new CurrentConversationHistoryAppender(memory,Optional.of(log));
    try(var scope=AgentRunScope.open(new AgentRunContext("run","",Path.of("."),"project",AgentRunContext.RequestSource.WEB))) {
      appender.appendUserMessage("url"); appender.appendAssistantMessage("summary");
    }
    verifyNoInteractions(memory,log);
  }
}
