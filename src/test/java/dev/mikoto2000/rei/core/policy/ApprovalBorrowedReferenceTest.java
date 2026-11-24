package dev.mikoto2000.rei.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.RunContinuation;
import dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

@org.junit.jupiter.api.Tag("integration")
class ApprovalBorrowedReferenceTest {
  @TempDir Path directory;

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void approvalRetainsOnlyAVerifiedBorrowedMessageReference(boolean verified) {
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("approval.db"));
    var payloads = new ModelMessagePayloadStore(source);
    var old = new AgentRunContext("previous", "chat", directory, "project");
    var current = new AgentRunContext("current", "chat", directory, "project");
    var previous = payloads.newExchange(old);
    var active = payloads.newExchange(current);
    var original = AssistantMessage.builder().content("previous native answer")
        .properties(Map.of("rei.llm.replay-origin-owner", previous.ownerKey(),
            "rei.llm.replay-origin-endpoint", "endpoint")).build();
    var reference = previous.reference(original);
    var pointer = payloads.historyReference(previous.scope(), reference, "history:previous");
    if (verified) payloads.historyReplayGrant(active, List.of(pointer),
        new ModelMessagePayloadStore.Origin(active.ownerKey(), "endpoint"));
    var call = new AssistantMessage.ToolCall("call", "function", "fixture", "{}");
    var pendingMessage = AssistantMessage.builder().content("pending").toolCalls(List.of(call))
        .properties(Map.of("rei.llm.replay-origin-owner", active.ownerKey(),
            "rei.llm.replay-origin-endpoint", "endpoint")).build();
    var pendingReference = active.reference(pendingMessage);
    var pending = new RunContinuation.PendingTools("pending", List.of(call), List.of(), Set.of(), pendingReference);
    var approvals = new ApprovalContinuationStore(source);
    Runnable save = () -> approvals.save("approval", current, "current request", List.of(
        ContextHistoryAdvisor.historical(original, 4), new UserMessage("current request")),
        pending, new OutputLimitRunBudget(2, 4).snapshot(), false);
    if (!verified) {
      org.assertj.core.api.Assertions.assertThatThrownBy(save::run)
          .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("previous native answer");
      assertThat(approvals.find("approval")).isEmpty();
      return;
    }
    save.run();
    var restarted = new ApprovalContinuationStore(source);
    var entry = restarted.find("approval").orElseThrow();
    assertThat(restarted.document(entry).history().getFirst().payload())
        .as("preserve the source Run identity and payload hash instead of copying foreign origin into the new Run")
        .isEqualTo(reference);
    assertThat(restarted.saved(entry).messages().getFirst().getMetadata())
        .containsEntry("rei.llm.replay-origin-owner", previous.ownerKey());
  }
}
