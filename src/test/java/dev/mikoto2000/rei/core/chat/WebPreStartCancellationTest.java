package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/** WebPre開始キャンセルの結果と状態の契約を検証するテスト。 */
class WebPreStartCancellationTest {
  /** 会話の実行の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void pendingCancellationDoesNotCreateTurnOrCallChatClient() {
    var cancellation = new CommandCancellationService();
    var client = mock(ChatClient.class);
    var turns = mock(ConversationTurnStore.class);
    var execution =
        new ChatExecutionService(
            client, mock(ModelHolderService.class), cancellation, Optional.empty());
    execution.setConversationTurnStore(turns);
    cancellation.cancelRun("run");
    try {
      assertThat(
              execution
                  .execute(
                      new AgentRunContext("run", "session", Path.of(".")),
                      "hello",
                      new UserInterventionQueue())
                  .status())
          .isEqualTo(ChatExecutionResult.Status.CANCELLED);
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(client, turns);
    } finally {
      Thread.interrupted();
    }
  }
}
