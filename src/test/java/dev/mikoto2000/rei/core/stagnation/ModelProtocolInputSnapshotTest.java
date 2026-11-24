package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** A durable checkpoint must retain the input actually sent, including borrowed history. */
@org.junit.jupiter.api.Tag("integration")
class ModelProtocolInputSnapshotTest {
  @TempDir Path directory;

  private RunExecutionContext execution(
      AgentRunContext owner,
      ModelMessagePayloadStore.Exchange exchange,
      ModelMessagePayloadStore.Origin origin) {
    var execution =
        new RunExecutionContext(
            owner.runId(),
            new OutputLimitRunBudget(2, 8),
            new ProgressEvaluator(directory),
            new AgentEventFactory(Clock.systemUTC()),
            ignored -> {});
    execution.setRunContext(owner);
    execution.setModelOptions(
        ToolCallingChatOptions.builder()
            .toolContext(
                Map.of(
                    ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY, exchange,
                    ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY, origin))
            .build());
    return execution;
  }

  @Test
  void resumedInputIncludesOriginalInstructionsBorrowedHistoryAndTheAcceptedResponse() {
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("protocol.db"));
    var payloads = new ModelMessagePayloadStore(source);
    var owner = new AgentRunContext("current", "chat", directory, "project");
    var exchange = payloads.newExchange(owner);
    var origin = new ModelMessagePayloadStore.Origin(exchange.ownerKey(), "endpoint");
    var old = payloads.newExchange(new AgentRunContext("old", "chat", directory, "project"));
    var borrowed =
        AssistantMessage.builder()
            .content("prior answer")
            .properties(
                Map.of(
                    "rei.llm.replay-origin-owner",
                    old.ownerKey(),
                    "rei.llm.replay-origin-endpoint",
                    "endpoint"))
            .build();
    var previousUser = new UserMessage("prior request");
    var previousUserRef = old.withKey("input").reference(previousUser);
    var borrowedRef = old.reference(borrowed);
    payloads.historyReplayGrant(
        exchange,
        List.of(payloads.historyReference(old.scope(), previousUserRef, "history:old"),
            payloads.historyReference(old.scope(), borrowedRef, "history:old")),
        origin);
    var execution = execution(owner, exchange, origin);
    var current = new UserMessage("current request");
    execution.preserveUserMessage(current);
    List<Message> actualInput =
        List.of(
            new SystemMessage("original instructions"),
            dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor.historical(new SystemMessage("retained summary"), 10),
            dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor.historical(previousUser, 1),
            dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor.historical(borrowed, 4),
            current);
    execution.captureModelInput(new Prompt(actualInput, execution.modelOptions()));
    var active =
        (ModelMessagePayloadStore.Exchange)
            execution
                .modelOptions()
                .getToolContext()
                .get(ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY);
    var answer =
        AssistantMessage.builder()
            .content("accepted answer")
            .properties(
                Map.of(
                    "rei.llm.replay-origin-owner",
                    origin.owner(),
                    "rei.llm.replay-origin-endpoint",
                    origin.endpoint()))
            .build();
    active.reference(answer);
    var history = execution.protocolHistorySnapshot();
    String checkpoint = ModelMessagePayloadStore.checkpointOwner("project", "task", 1);
    payloads.protectCheckpoint(exchange.scope(), history, checkpoint);
    var references =
        history.replayEntries().stream()
            .map(entry ->
                new ModelMessagePayloadStore.ReplayReference(
                    entry.reference(),
                    ModelMessagePayloadStore.ReferenceKind.CHECKPOINT,
                    checkpoint))
            .toList();
    var next = new AgentRunContext("next", "chat", directory, "project");
    var target = payloads.newExchange(next);
    var restored =
        execution(next, target, new ModelMessagePayloadStore.Origin(target.ownerKey(), "endpoint"));
    restored.restoreProgress(execution.progressSnapshot(), references);
    var expected = new java.util.ArrayList<>(actualInput);
    expected.add(answer);
    assertThat(restored.takeResumeMessages()).containsExactlyElementsOf(expected);
    assertThat(payloads.load(exchange.scope(), borrowedRef).getMetadata())
        .containsEntry("rei.llm.replay-origin-owner", old.ownerKey())
        .doesNotContainKey("rei.historySequence");
    assertThat(execution.protocolHistoryReferences())
        .as("system instructions and prior completed history are not new turn output")
        .hasSize(2);
  }
}
