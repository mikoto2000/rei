package dev.mikoto2000.rei.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.RunContinuation;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;

class ApprovalProtocolHistoryTest {
  @TempDir Path directory;

  @Test
  void restartRestoresExactProtocolHistoryWithoutInliningOpaqueInContinuation() throws Exception {
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("approval.db"));
    var owner = new AgentRunContext("run", "session", directory, "project");
    var original = AssistantMessage.builder().parts(List.of(
        new ReasoningPart("", "", new OpaquePayload("fixture", "encrypted_content", "opaque-fixture+/="),
            Map.of("item_id", "reasoning-item", "status", "completed")),
        TextPart.of("completed prefix")))
        .properties(Map.of("rei.llm.replay-origin-owner", "session",
            "rei.llm.replay-origin-endpoint", "fingerprint")).build();
    var pending = new RunContinuation.PendingTools("pending", List.of(
        new AssistantMessage.ToolCall("call", "function", "fixture", "{}")), List.of());
    new ApprovalContinuationStore(source).save("approval", owner, "request", List.of(original),
        pending, new OutputLimitRunBudget(0, 5).snapshot(), false);
    var restarted = new ApprovalContinuationStore(source);
    var entry = restarted.find("approval").orElseThrow();
    var restored = (AssistantMessage) restarted.saved(entry).messages().getFirst();
    assertThat(restored.getParts()).containsExactlyElementsOf(original.getParts());
    assertThat(restored.getMetadata()).containsAllEntriesOf(original.getMetadata());
    assertThat(entry.document()).doesNotContain("opaque-fixture");
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var document = json.readTree(entry.document());
    ((com.fasterxml.jackson.databind.node.ObjectNode) document.path("history").get(0)).remove("payload");
    var missing = new ApprovalContinuationStore.Entry(entry.approvalId(), entry.runId(), entry.state(),
        json.writeValueAsString(document), entry.pid(), entry.started());
    assertThatThrownBy(() -> restarted.saved(missing)).isInstanceOf(IllegalStateException.class);
  }
}
