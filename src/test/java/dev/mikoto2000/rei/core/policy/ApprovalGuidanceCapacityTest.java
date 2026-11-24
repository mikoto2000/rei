package dev.mikoto2000.rei.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

@org.junit.jupiter.api.Tag("integration")
class ApprovalGuidanceCapacityTest {
  @TempDir Path directory;

  @Test
  void guidanceCapacityCountsUtf8BytesBeforeReplacingTheSavedDocument() {
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("guidance.db"));
    var store = new ApprovalContinuationStore(source);
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext("run", "chat", directory, "project");
    store.save(
        "approval",
        owner,
        "request",
        List.of(new UserMessage("original")),
        new dev.mikoto2000.rei.core.chat.RunContinuation.PendingTools(
            "pending",
            List.of(new AssistantMessage.ToolCall("call", "function", "fixture", "{}")),
            List.of()),
        new dev.mikoto2000.rei.llm.OutputLimitRunBudget(0, 5).snapshot(),
        false);
    String original = store.find("approval").orElseThrow().document();
    assertThatThrownBy(() -> store.guidance(owner, List.of(new UserMessage("界".repeat(400000)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capacity");
    assertThat(store.find("approval").orElseThrow().document()).isEqualTo(original);
  }
}
