package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** A checkpoint is the authority for cross-Run recovery, including after old Run leases end. */
@org.junit.jupiter.api.Tag("integration")
class CheckpointProtocolResumeTest {
  @TempDir Path directory;

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"compatible", "endpoint", "missing", "incomplete"})
  void checkpointReferencesRestoreOnlyACompatibleCompletedProtocolPrefix(String condition) {
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("protocol.db"));
    var store = new ModelMessagePayloadStore(source);
    var old = store.newExchange(new AgentRunContext("old", "chat", directory, "project"));
    var oldOrigin = new ModelMessagePayloadStore.Origin(old.ownerKey(), "endpoint");
    var first = new AssistantMessage.ToolCall("one", "function", "fixture", "{}");
    var second = new AssistantMessage.ToolCall("two", "function", "fixture", "{}");
    var userRef = old.withKey("input").reference(new UserMessage("original input"));
    var assistant =
        AssistantMessage.builder()
            .toolCalls(List.of(first, second))
            .content("pending")
            .properties(
                Map.of(
                    "rei.llm.replay-origin-owner",
                    old.ownerKey(),
                    "rei.llm.replay-origin-endpoint",
                    "endpoint"))
            .build();
    var assistantRef = old.reference(assistant);
    var results =
        condition.equals("incomplete")
            ? List.of(new ToolResponseMessage.ToolResponse("one", "fixture", "first"))
            : List.of(
                new ToolResponseMessage.ToolResponse("one", "fixture", "first"),
                new ToolResponseMessage.ToolResponse("two", "fixture", "second"));
    var resultRef = old.reference(ToolResponseMessage.builder().responses(results).build());
    var history =
        new ModelMessagePayloadStore.History(
            1,
            "old",
            oldOrigin,
            List.of(
                new ModelMessagePayloadStore.History.Entry("input/USER", userRef),
                new ModelMessagePayloadStore.History.Entry(old.key() + "/ASSISTANT", assistantRef),
                new ModelMessagePayloadStore.History.Entry(old.key() + "/TOOL", resultRef)));
    String checkpointOwner = ModelMessagePayloadStore.checkpointOwner("project", "task", 1);
    store.protectCheckpoint(old.scope(), history, checkpointOwner);
    var references =
        history.entries().stream()
            .map(entry ->
                new ModelMessagePayloadStore.ReplayReference(
                    entry.reference(),
                    ModelMessagePayloadStore.ReferenceKind.CHECKPOINT,
                    condition.equals("missing") ? "unpublished-revision" : checkpointOwner))
            .toList();
    for (var entry : history.entries())
      store.release(
          old.scope(), entry.reference(), ModelMessagePayloadStore.ReferenceKind.RUN, "old");
    var current = new AgentRunContext("new", "chat", directory, "project");
    var exchange = store.newExchange(current);
    var origin =
        new ModelMessagePayloadStore.Origin(
            exchange.ownerKey(), condition.equals("endpoint") ? "changed-endpoint" : "endpoint");
    var execution =
        new RunExecutionContext(
            "new",
            new OutputLimitRunBudget(2, 8),
            new ProgressEvaluator(directory),
            new AgentEventFactory(Clock.systemUTC()),
            ignored -> {});
    execution.setRunContext(current);
    execution.setModelOptions(
        ToolCallingChatOptions.builder()
            .toolContext(
                Map.of(
                    ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY, exchange,
                    ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY, origin))
            .build());
    var progress =
        new RunExecutionContext.ProgressState(
            null, new OutputLimitRunBudget(2, 8).snapshot(), 0, 0, 0, null, history);
    if (!condition.equals("compatible")) {
      assertThatThrownBy(() -> execution.restoreProgress(progress, references))
          .isInstanceOf(IllegalStateException.class);
      assertThat(execution.protocolHistorySnapshot()).isNull();
      assertThat(execution.takeResumeMessages()).isEmpty();
      return;
    }
    execution.restoreProgress(progress, references);
    assertThat(execution.protocolHistorySnapshot().run()).isEqualTo("new");
    assertThat(execution.protocolHistorySnapshot().entries()).isEqualTo(history.entries());
    execution.preserveUserMessage(new UserMessage("host checkpoint resume control"));
    assertThat(execution.protocolHistorySnapshot().entries())
        .as("do not archive a replaced host control as model input")
        .isEqualTo(history.entries());
    assertThat(execution.takeResumeMessages()).hasSize(3).element(1).isEqualTo(assistant);
    assertThat(execution.modelOptions().getToolContext())
        .containsKey(ModelMessagePayloadStore.HISTORY_GRANT_CONTEXT_KEY);
    var active =
        (ModelMessagePayloadStore.Exchange)
            execution
                .modelOptions()
                .getToolContext()
                .get(ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY);
    active.reference(
        AssistantMessage.builder()
            .content("continued")
            .properties(
                Map.of(
                    "rei.llm.replay-origin-owner",
                    origin.owner(),
                    "rei.llm.replay-origin-endpoint",
                    origin.endpoint()))
            .build());
    assertThat(execution.protocolHistoryReferences()).hasSize(4);
    assertThat(store.load(old.scope(), assistantRef).getMetadata())
        .containsEntry("rei.llm.replay-origin-owner", old.ownerKey());
  }
}
