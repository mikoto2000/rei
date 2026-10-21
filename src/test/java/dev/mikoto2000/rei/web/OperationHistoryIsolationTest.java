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

/** 操作履歴作業の分離の結果と状態の契約を検証するテスト。 */
class OperationHistoryIsolationTest {
  /** Web APIによる操作で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
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
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(memory, log);
  }
}
