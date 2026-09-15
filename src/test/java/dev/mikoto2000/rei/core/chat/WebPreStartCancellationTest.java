package dev.mikoto2000.rei.core.chat;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import dev.mikoto2000.rei.core.service.*;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebPreStartCancellationTest {
  @Test void pendingCancellationDoesNotCreateTurnOrCallChatClient() {
    var cancellation = new CommandCancellationService();
    var client = mock(ChatClient.class);
    var turns = mock(ConversationTurnStore.class);
    var execution = new ChatExecutionService(client, mock(ModelHolderService.class), cancellation, Optional.empty());
    execution.setConversationTurnStore(turns);
    cancellation.cancelRun("run");
    try {
      assertThat(execution.execute(new AgentRunContext("run", "session", Path.of(".")), "hello", new UserInterventionQueue()).status())
          .isEqualTo(ChatExecutionResult.Status.CANCELLED);
      verifyNoInteractions(client, turns);
    } finally { Thread.interrupted(); }
  }
}
