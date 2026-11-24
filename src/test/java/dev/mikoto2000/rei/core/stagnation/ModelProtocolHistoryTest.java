package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** Protocol pointers in existing Run progress, using only temporary SQLite storage. */
class ModelProtocolHistoryTest {
  @TempDir Path directory;

  record Fixture(RunExecutionContext execution, ModelMessagePayloadStore.Exchange exchange) {}

  Fixture fixture() {
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("protocol.db"));
    var store = new ModelMessagePayloadStore(source);
    var owner = new AgentRunContext("run", "chat", directory, "project");
    var exchange = store.newExchange(owner);
    var origin = new ModelMessagePayloadStore.Origin(exchange.ownerKey(), "endpoint");
    var execution = new RunExecutionContext("run", new OutputLimitRunBudget(2, 5),
        new ProgressEvaluator(directory), new dev.mikoto2000.rei.event.AgentEventFactory(
            java.time.Clock.systemUTC()), ignored -> {});
    execution.setRunContext(owner);
    execution.setModelOptions(ToolCallingChatOptions.builder().toolContext(Map.of(
        ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY, exchange,
        ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY, origin)).build());
    return new Fixture(execution, (ModelMessagePayloadStore.Exchange) execution.modelOptions()
        .getToolContext().get(ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY));
  }

  @Test
  void completedToolBatchReplacesItsCheckpointPrefixesWithoutRepeatingOutputs() {
    var fixture = fixture();
    var exchange = fixture.exchange();
    var assistant = AssistantMessage.builder().content("working")
        .toolCalls(List.of(new AssistantMessage.ToolCall("one", "function", "fixture", "{}"),
            new AssistantMessage.ToolCall("two", "function", "fixture", "{}")))
        .properties(Map.of("rei.llm.replay-origin-owner", exchange.ownerKey(),
            "rei.llm.replay-origin-endpoint", "endpoint")).build();
    exchange.persist(assistant);
    var first = new ToolResponseMessage.ToolResponse("one", "fixture", "first-result");
    var second = new ToolResponseMessage.ToolResponse("two", "fixture", "second-result");
    exchange.withKey(exchange.key() + "/prefix:1")
        .persist(ToolResponseMessage.builder().responses(List.of(first)).build());
    var complete = ToolResponseMessage.builder().responses(List.of(first, second)).build();
    exchange.withKey(exchange.key() + "/prefix:2").persist(complete);
    exchange.persist(complete);
    var history = fixture.execution().progressSnapshot().protocolHistory();
    assertThat(history.entries()).as("one assistant and one cumulative tool-result batch").hasSize(2);
    var messages = history.entries().stream().map(entry -> exchange.store().load(
        exchange.scope(), entry.reference(), ModelMessagePayloadStore.ReferenceKind.RUN, "run")).toList();
    assertThat(messages.getFirst()).isEqualTo(assistant);
    assertThat(((ToolResponseMessage) messages.getLast()).getResponses()).containsExactly(first, second);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void lateOrConflictingPrefixesCannotReplaceAcceptedResults(boolean conflicting) {
    var fixture = fixture();
    var exchange = fixture.exchange();
    exchange.persist(AssistantMessage.builder().content("working")
        .toolCalls(List.of(new AssistantMessage.ToolCall("one", "function", "fixture", "{}"),
            new AssistantMessage.ToolCall("two", "function", "fixture", "{}")))
        .properties(Map.of("rei.llm.replay-origin-owner", exchange.ownerKey(),
            "rei.llm.replay-origin-endpoint", "endpoint")).build());
    var first = new ToolResponseMessage.ToolResponse("one", "fixture", "original-result");
    var prefix = ToolResponseMessage.builder().responses(List.of(first)).build();
    exchange.withKey(exchange.key() + "/prefix:1").persist(prefix);
    var second = new ToolResponseMessage.ToolResponse("two", "fixture", "second-result");
    var complete = ToolResponseMessage.builder().responses(List.of(
        conflicting ? new ToolResponseMessage.ToolResponse("one", "fixture", "changed-result") : first,
        second)).build();
    if (conflicting) {
      org.assertj.core.api.Assertions.assertThatThrownBy(() ->
          exchange.withKey(exchange.key() + "/prefix:2").persist(complete))
          .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("original-result")
          .hasMessageNotContaining("changed-result");
    } else {
      exchange.withKey(exchange.key() + "/prefix:2").persist(complete);
      exchange.withKey(exchange.key() + "/prefix:1").persist(prefix);
    }
    var history = fixture.execution().progressSnapshot().protocolHistory();
    assertThat(history.entries()).hasSize(2);
    var last = (ToolResponseMessage) exchange.store().load(exchange.scope(),
        history.entries().getLast().reference(), ModelMessagePayloadStore.ReferenceKind.RUN, "run");
    assertThat(last.getResponses()).containsExactlyElementsOf(conflicting ? List.of(first) : List.of(first, second));
  }

  @Test
  void originalMediaBytesRemainRecoverableAfterTheAttachmentChanges() throws Exception {
    var fixture = fixture();
    var image = directory.resolve("attachment.png");
    java.nio.file.Files.write(image, new byte[] {1, 2, 3});
    var user = UserMessage.builder().text("inspect image").media(new org.springframework.ai.content.Media(
        org.springframework.util.MimeTypeUtils.IMAGE_PNG,
        new org.springframework.core.io.FileSystemResource(image))).build();
    fixture.execution().preserveUserMessage(user);
    java.nio.file.Files.write(image, new byte[] {4, 5, 6});
    var history = fixture.execution().progressSnapshot().protocolHistory();
    assertThat(history.entries()).hasSize(1);
    var loaded = (UserMessage) fixture.exchange().store().load(fixture.exchange().scope(),
        history.entries().getFirst().reference(), ModelMessagePayloadStore.ReferenceKind.RUN, "run");
    assertThat(loaded.getMedia().getFirst().getDataAsByteArray()).containsExactly((byte) 1, (byte) 2, (byte) 3);
  }

  @Test
  void legacyProgressDoesNotAddAProtocolHistoryProperty() throws Exception {
    var legacy = new RunExecutionContext.ProgressState(null, null, 0, 0);
    assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(legacy))
        .doesNotContain("protocolHistory");
  }
}
