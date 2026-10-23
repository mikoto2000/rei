package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class ConversationLifecycleAdvisorTest {
  @Test
  void cancelledHistoryPreservesResumeMetadataAndOtherSystemContexts() {
    var turns = mock(ConversationTurnStore.class);
    when(turns.cancelledContext("conversation")).thenReturn("Cancelled history; explicit resume allowed");
    var handoff = SystemMessage.builder().text("verified handoff")
        .metadata(Map.of("rei.resumeContext", true, "rei.resumeRunId", "current",
            "rei.knownFileInventory", true)).build();
    var other = new SystemMessage("other context");
    var request = ChatClientRequest.builder().prompt(new Prompt(
        List.of(handoff, other, new UserMessage("Resume")))).build();
    var result = new ConversationLifecycleAdvisor(turns, "conversation").before(request, null).prompt();
    assertThat(result.getInstructions().getFirst().getMetadata()).isEqualTo(handoff.getMetadata());
    assertThat(result.getInstructions().getFirst().getText()).contains("verified handoff", "Cancelled history");
    assertThat(result.getInstructions().get(1)).isSameAs(other);
  }
}
