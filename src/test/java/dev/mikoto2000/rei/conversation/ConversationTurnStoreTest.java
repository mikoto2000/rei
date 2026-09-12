package dev.mikoto2000.rei.conversation;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import static org.assertj.core.api.Assertions.*;

class ConversationTurnStoreTest {
  @TempDir Path temp;
  @Test void cancellationSurvivesRestartAndUnrelatedCompletedTurnsWithoutCrossConversationLeak() {
    var store = new ConversationTurnStore(temp);
    var a = new AgentRunContext("a", "chat:main", temp);
    store.start(a, "write network.md");
    store.finish(a, ConversationTurnStore.Status.CANCELLED);
    var greeting = new AgentRunContext("b", "chat:main", temp);
    store.start(greeting, "hello");
    store.finish(greeting, ConversationTurnStore.Status.COMPLETED);
    var restored = new ConversationTurnStore(temp);
    assertThat(restored.read("chat:main")).extracting(ConversationTurnStore.Turn::status)
        .containsExactly(ConversationTurnStore.Status.CANCELLED, ConversationTurnStore.Status.COMPLETED);
    assertThat(restored.cancelledContext("chat:main")).contains("write network.md", "Explicit resumption is allowed");
    assertThat(restored.cancelledContext("chat:other")).isEmpty();
    restored.finish(a, ConversationTurnStore.Status.FAILED);
    assertThat(restored.read("chat:main").getFirst().status()).isEqualTo(ConversationTurnStore.Status.CANCELLED);
  }
}
