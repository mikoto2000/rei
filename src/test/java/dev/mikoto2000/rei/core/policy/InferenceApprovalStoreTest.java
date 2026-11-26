package dev.mikoto2000.rei.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.llm.LlmApiType;
import dev.mikoto2000.rei.llm.LlmAuthentication;
import dev.mikoto2000.rei.llm.LlmCapabilities;
import dev.mikoto2000.rei.llm.LlmProvider;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.llm.ReasoningReplayMode;
import dev.mikoto2000.rei.llm.ResolvedLlmEndpoint;
import dev.mikoto2000.rei.llm.RunLlmConnection;
import dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

/** Existing protected input references and SQL continuations; no inference or fake tool call. */
class InferenceApprovalStoreTest {
  @TempDir Path directory;

  @Test
  void preInferenceWaitSurvivesReopenWithoutInventingAToolTransaction() {
    var fixture = fixture();
    var store = new ApprovalContinuationStore(fixture.source());
    assertThatCode(() ->
            store.saveInference(
                "approval",
                fixture.owner(),
                "fixture user",
                budget(),
                false,
                fixture.history(),
                fixture.connection(),
                input()))
        .doesNotThrowAnyException();
    var reopened = new ApprovalContinuationStore(fixture.source());
    var entry = reopened.find("approval").orElseThrow();
    assertThat(entry.state()).isEqualTo("WAITING_APPROVAL");
    var document = reopened.document(entry);
    assertThat(document.pending()).isNull();
    assertThat(document.inference()).isEqualTo(input());
    assertThat(document.connection()).isEqualTo(fixture.connection());
    assertThat(entry.document()).doesNotContain("PRIVATE SYSTEM FIXTURE");
    var saved = reopened.saved(entry);
    assertThat(saved.pendingTools()).isNull();
    assertThat(saved.messages())
        .extracting(Message::getText)
        .containsExactly("PRIVATE SYSTEM FIXTURE", "fixture user");
    assertThat(saved.connection()).isEqualTo(fixture.connection());
    assertThat(saved.protocolHistory()).isEqualTo(fixture.history());
  }

  @Test
  void wrongConnectionOriginCannotProtectOrSaveAnInferenceWait() {
    var fixture = fixture();
    var store = new ApprovalContinuationStore(fixture.source());
    var history = fixture.history();
    var foreign =
        new ModelMessagePayloadStore.History(
            history.schemaVersion(),
            history.run(),
            new ModelMessagePayloadStore.Origin(history.origin().owner(), "d".repeat(64)),
            history.entries(),
            history.input());
    assertThatThrownBy(() ->
            store.saveInference(
                "approval",
                fixture.owner(),
                "fixture user",
                budget(),
                false,
                foreign,
                fixture.connection(),
                input()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ownership");
    assertThat(store.find("approval")).isEmpty();
  }

  @Test
  void inferenceWaitRequiresTheActualCompleteInputSnapshot() {
    var fixture = fixture();
    var store = new ApprovalContinuationStore(fixture.source());
    var history = fixture.history();
    var missing =
        new ModelMessagePayloadStore.History(
            history.schemaVersion(), history.run(), history.origin(), history.entries());
    assertThatThrownBy(() ->
            store.saveInference(
                "approval",
                fixture.owner(),
                "fixture user",
                budget(),
                false,
                missing,
                fixture.connection(),
                input()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("input");
    assertThat(store.find("approval")).isEmpty();
  }

  @Test
  void ordinaryToolSaveStillRequiresAPendingTool() {
    var fixture = fixture();
    var store = new ApprovalContinuationStore(fixture.source());
    assertThatThrownBy(() ->
            store.save(
                "approval", fixture.owner(), "fixture user", List.of(), null, budget(), false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Pending Tool");
    assertThat(store.find("approval")).isEmpty();
  }

  private Fixture fixture() {
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("continuation.db"));
    var owner = new AgentRunContext("run", "session", directory, "project");
    var connection =
        new RunLlmConnection(
            "local",
            true,
            LlmProvider.OPENAI_COMPATIBLE,
            LlmAuthentication.NONE,
            null,
            null,
            new ResolvedLlmEndpoint(
                "local",
                1,
                LlmApiType.RESPONSES,
                "http://fixture.invalid/v1",
                "chat-endpoint:local",
                "fixture-model",
                new LlmCapabilities(ReasoningReplayMode.NONE, 8, Set.of()),
                null,
                null,
                Duration.ofSeconds(5)),
            Map.of(),
            Map.of());
    var payloads = new ModelMessagePayloadStore(source);
    var exchange = payloads.newExchange(owner);
    var messages =
        List.<Message>of(
            new SystemMessage("PRIVATE SYSTEM FIXTURE"), new UserMessage("fixture user"));
    var entries = new java.util.ArrayList<ModelMessagePayloadStore.History.Entry>();
    for (var message : messages) {
      String key = "input:" + entries.size();
      var reference = payloads.saveForContinuation(exchange.scope(), owner.runId(), key, message);
      payloads.publish(
          exchange.scope(), reference, ModelMessagePayloadStore.ReferenceKind.RUN, owner.runId());
      entries.add(new ModelMessagePayloadStore.History.Entry(key, reference));
    }
    var history =
        new ModelMessagePayloadStore.History(
            1,
            owner.runId(),
            new ModelMessagePayloadStore.Origin(exchange.ownerKey(), connection.fingerprint()),
            List.of(),
            new ModelMessagePayloadStore.History.Input(entries, 0, "b".repeat(64)));
    return new Fixture(source, owner, connection, history);
  }

  private static OutputLimitRunBudget.Snapshot budget() {
    return new OutputLimitRunBudget.Snapshot(0, 1, 0, false);
  }

  private static ApprovalContinuationStore.InferenceInput input() {
    return new ApprovalContinuationStore.InferenceInput(
        "c".repeat(64),
        List.of(
            new ApprovalContinuationStore.InferenceTool(
                "searchTools", "local tools", "{\"type\":\"object\"}", false, false)));
  }

  private record Fixture(
      org.sqlite.SQLiteDataSource source,
      AgentRunContext owner,
      RunLlmConnection connection,
      ModelMessagePayloadStore.History history) {}
}
