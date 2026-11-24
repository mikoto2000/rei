package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.RunContinuation;
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
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** A checkpoint-derived trace can contain verified original messages from a prior Run. */
@org.junit.jupiter.api.Tag("integration")
class ApprovalTraceAuthorityTest {
  @TempDir Path directory;

  @Test
  void approvalCanRestoreAnAlreadyVerifiedCheckpointTraceWithoutRewritingItsSourceOwner() {
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("protocol.db"));
    var payloads = new ModelMessagePayloadStore(source);
    var current = new AgentRunContext("current", "chat", directory, "project");
    var old = payloads.newExchange(new AgentRunContext("old", "chat", directory, "project"));
    var exchange = payloads.newExchange(current);
    var origin = new ModelMessagePayloadStore.Origin(exchange.ownerKey(), "endpoint");
    var oldMessage =
        AssistantMessage.builder()
            .content("saved checkpoint result")
            .properties(
                Map.of(
                    "rei.llm.replay-origin-owner",
                    old.ownerKey(),
                    "rei.llm.replay-origin-endpoint",
                    "endpoint"))
            .build();
    var oldReference = old.reference(oldMessage);
    var currentReference =
        exchange.reference(
            AssistantMessage.builder()
                .content("pending approval")
                .properties(
                    Map.of(
                        "rei.llm.replay-origin-owner",
                        origin.owner(),
                        "rei.llm.replay-origin-endpoint",
                        origin.endpoint()))
                .build());
    var references =
        List.of(oldReference, currentReference).stream()
            .map(ref -> {
              payloads.publish(
                  exchange.scope(),
                  ref,
                  ModelMessagePayloadStore.ReferenceKind.APPROVAL,
                  "approval");
              return new ModelMessagePayloadStore.ReplayReference(
                  ref, ModelMessagePayloadStore.ReferenceKind.APPROVAL, "approval");
            })
            .toList();
    var history =
        new ModelMessagePayloadStore.History(
            1,
            current.runId(),
            origin,
            List.of(
                new ModelMessagePayloadStore.History.Entry(old.key() + "/ASSISTANT", oldReference),
                new ModelMessagePayloadStore.History.Entry(
                    exchange.key() + "/ASSISTANT", currentReference)));
    var execution =
        new RunExecutionContext(
            current.runId(),
            new OutputLimitRunBudget(2, 8),
            new ProgressEvaluator(directory),
            new AgentEventFactory(Clock.systemUTC()),
            ignored -> {});
    execution.setRunContext(current);
    execution.resume(
        new RunContinuation.Saved(
            "fixture",
            List.of(oldMessage),
            new OutputLimitRunBudget(2, 8).snapshot(),
            null,
            references,
            history));
    execution.setModelOptions(
        ToolCallingChatOptions.builder()
            .toolContext(
                Map.of(
                    ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY, exchange,
                    ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY, origin))
            .build());
    assertThat(execution.protocolHistorySnapshot()).isEqualTo(history);
    assertThat(payloads.load(exchange.scope(), oldReference).getMetadata())
        .containsEntry("rei.llm.replay-origin-owner", old.ownerKey());
  }
}
