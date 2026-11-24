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
  void completedPrefixAndOriginalPartsSurvivePendingApprovalRestart() throws Exception {
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("pending.db"));
    var owner = new AgentRunContext("run", "session", directory, "project");
    var payloads = new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore(source);
    var scope = dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope.forOwner(owner);
    var calls = List.of(new AssistantMessage.ToolCall("first", "function", "fixture", "{\"index\":1}"),
        new AssistantMessage.ToolCall("second", "function", "fixture", "{\"index\":2}"));
    var original = AssistantMessage.builder().parts(List.of(
        new ReasoningPart("", "", new OpaquePayload("fixture", "encrypted_content", "pending-opaque-one"),
            Map.of("item_id", "reasoning-one")),
        org.springframework.ai.chat.messages.part.ToolCallPart.of(calls.getFirst()),
        new ReasoningPart("", "", new OpaquePayload("fixture", "encrypted_content", "pending-opaque-two"),
            Map.of("item_id", "reasoning-two")),
        org.springframework.ai.chat.messages.part.ToolCallPart.of(calls.getLast()))).build();
    var callback = org.mockito.Mockito.mock(org.springframework.ai.tool.ToolCallback.class);
    org.mockito.Mockito.when(callback.getToolDefinition()).thenReturn(
        org.springframework.ai.tool.definition.ToolDefinition.builder().name("fixture")
            .description("fixture").inputSchema("{\"type\":\"object\"}").build());
    org.mockito.Mockito.when(callback.getToolMetadata()).thenReturn(
        org.springframework.ai.tool.metadata.ToolMetadata.builder().returnDirect(false).build());
    var prefixCalls = new java.util.concurrent.atomic.AtomicInteger();
    org.mockito.Mockito.when(callback.call(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> {
          if (invocation.<String>getArgument(0).contains(":1")) {
            prefixCalls.incrementAndGet(); return "saved prefix";
          }
          throw new ToolPermissionException("fixture", PermissionDecision.REQUIRE_APPROVAL).withApprovalId("approval");
        });
    var context = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.class);
    org.mockito.Mockito.when(context.hasApprovalContinuation()).thenReturn(true);
    org.mockito.Mockito.when(context.suspendForApproval(org.mockito.ArgumentMatchers.eq("approval"),
        org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> {
          new ApprovalContinuationStore(source).save("approval", owner, "request", invocation.getArgument(1),
              invocation.getArgument(2), new OutputLimitRunBudget(0, 5).snapshot(), false);
          return true;
        });
    var options = org.springframework.ai.model.tool.ToolCallingChatOptions.builder().toolCallbacks(callback)
        .toolContext(Map.of(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY,
            new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange(payloads, scope, "run", "original")))
        .build();
    var prompt = new org.springframework.ai.chat.prompt.Prompt("request", options);
    var tools = new dev.mikoto2000.rei.core.chat.ToolLoopSupport();
    assertThatThrownBy(() -> tools.executeResumable(prompt, new org.springframework.ai.chat.model.ChatResponse(
        List.of(new org.springframework.ai.chat.model.Generation(original))), context))
        .isInstanceOf(dev.mikoto2000.rei.core.chat.RunWaitingException.class);
    var restarted = new ApprovalContinuationStore(source);
    var entry = restarted.find("approval").orElseThrow();
    var saved = restarted.saved(entry);
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var broken = json.readTree(entry.document());
    ((com.fasterxml.jackson.databind.node.ObjectNode) broken.path("pending")).remove("assistantReference");
    var missing = new ApprovalContinuationStore.Entry(entry.approvalId(), entry.runId(), entry.state(),
        json.writeValueAsString(broken), entry.pid(), entry.started());
    assertThatThrownBy(() -> restarted.saved(missing))
        .isInstanceOf(IllegalStateException.class).hasMessage("Invalid approval continuation").hasNoCause();
    org.mockito.Mockito.doReturn("approved result").when(callback)
        .call(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    var resumedOptions = options.mutate().toolContext(Map.of(
        dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY,
        new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange(
            new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore(source), scope, "run", "resumed"))).build();
    var result = tools.executePending(new org.springframework.ai.chat.prompt.Prompt(saved.messages(), resumedOptions),
        saved.pendingTools(), context);
    var restored = (AssistantMessage) result.conversationHistory().get(saved.messages().size());
    assertThat(restored.getParts()).containsExactlyElementsOf(original.getParts());
    assertThat(prefixCalls).hasValue(1);
    assertThat(((org.springframework.ai.chat.messages.ToolResponseMessage) result.conversationHistory().getLast())
        .getResponses()).extracting(org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse::responseData)
        .containsExactly("saved prefix", "approved result");
    assertThat(entry.document()).doesNotContain("pending-opaque");
  }

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
