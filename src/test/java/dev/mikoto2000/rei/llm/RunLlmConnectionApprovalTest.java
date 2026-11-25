package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.RunContinuation;
import dev.mikoto2000.rei.core.policy.ApprovalContinuationStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

class RunLlmConnectionApprovalTest {
  @TempDir Path root;

  @Test
  void reopenedApprovalRetainsItsAcceptedConnection() {
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("approval.db"));
    var store = new ApprovalContinuationStore(source);
    var owner = new AgentRunContext("run", "conversation", root, "project",
        AgentRunContext.RequestSource.SHELL, AgentRunContext.Mode.EXCLUSIVE);
    var pending = new RunContinuation.PendingTools("", List.of(
        new AssistantMessage.ToolCall("call", "function", "read", "{}")), List.of());
    var connection = connection();
    store.save("approval", owner, "request", List.of(new UserMessage("request")), pending,
        new OutputLimitRunBudget.Snapshot(0, 1, 0, false), false, null, connection);
    var reopened = new ApprovalContinuationStore(source);
    var entry = reopened.find("approval").orElseThrow();
    assertThat(reopened.document(entry).connection()).isEqualTo(connection);
    assertThat(reopened.saved(entry).connection()).isEqualTo(connection);
  }

  @Test
  void legacyContinuationStillHasNoConnection() {
    assertThat(new RunContinuation.Saved("request", List.of(),
        new OutputLimitRunBudget.Snapshot(0, 1, 0, false)).connection()).isNull();
  }

  private RunLlmConnection connection() {
    var endpoint = new ResolvedLlmEndpoint("local", 1, LlmApiType.CHAT_COMPLETIONS,
        "http://local.invalid/v1", "chat-endpoint:local", "model",
        new LlmCapabilities(ReasoningReplayMode.NONE, 36, Set.of()),
        512, null, Duration.ofSeconds(30));
    return new RunLlmConnection("local", true, LlmProvider.OPENAI_COMPATIBLE,
        LlmAuthentication.NONE, null, null, endpoint, Map.of(), Map.of("max_tokens", 512));
  }
}
