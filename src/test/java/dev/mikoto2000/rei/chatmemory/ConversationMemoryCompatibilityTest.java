package dev.mikoto2000.rei.chatmemory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

/** Characterizes the existing bounded memory contract independently of the retired API. */
class ConversationMemoryCompatibilityTest {
  @Test
  void evictionPreservesSystemMessagesAndStartsAtTheNextCompleteUserTurn() {
    var memory = WindowedConversationMemory.builder().maxMessages(4).build();
    var rules = new SystemMessage("rules");
    memory.add("a", List.of(rules, new UserMessage("first"), new AssistantMessage("reply")));
    memory.add("a", List.of(new UserMessage("second"), new AssistantMessage("answer")));
    assertThat(memory.get("a"))
        .extracting(m -> m.getText())
        .containsExactly("rules", "second", "answer");
    memory.add("a", new SystemMessage("new rules"));
    assertThat(memory.get("a"))
        .extracting(m -> m.getText())
        .containsExactly("second", "answer", "new rules");
  }

  @Test
  void repeatedSystemMessagesAndWindowsContainingOnlySystemMessagesAreRetained() {
    var memory = WindowedConversationMemory.builder().maxMessages(1).build();
    var rules = new SystemMessage("rules");
    memory.add("a", rules);
    memory.add("a", rules);
    assertThat(memory.get("a")).containsExactly(rules, rules);
  }

  @Test
  void readsAreDefensiveAndClearingOneConversationDoesNotClearAnother() {
    var memory = WindowedConversationMemory.builder().build();
    memory.add("a", new UserMessage("one"));
    memory.add("b", new UserMessage("two"));
    memory.get("a").clear();
    assertThat(memory.get("a")).extracting(m -> m.getText()).containsExactly("one");
    memory.clear("a");
    assertThat(memory.get("a")).isEmpty();
    assertThat(memory.get("b")).extracting(m -> m.getText()).containsExactly("two");
    assertThat(org.springframework.ai.chat.client.advisor.api.MemoryAdvisor.CONVERSATION_ID)
        .isEqualTo("chat_memory_conversation_id");
  }
}
