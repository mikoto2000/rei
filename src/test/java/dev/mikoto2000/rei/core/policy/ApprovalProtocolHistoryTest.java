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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"generation", "owner", "model", "api", "url", "mode", "credential", "same"})
  void changedEndpointOrReplayOwnerStopsBeforeSavedToolDispatch(String change) {
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("binding.db"));
    var owner = new AgentRunContext("run", "session", directory, "project");
    var scope = dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope.forOwner(owner);
    var payloads = new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore(source);
    var initial = new dev.mikoto2000.rei.llm.ResolvedLlmEndpoint("fixture", 1,
        dev.mikoto2000.rei.llm.LlmApiType.RESPONSES, "https://fixture.invalid", "credential", "fixture-model",
        new dev.mikoto2000.rei.llm.LlmCapabilities(dev.mikoto2000.rei.llm.ReasoningReplayMode.ENCRYPTED, 128),
        64, null, java.time.Duration.ofSeconds(5));
    var current = new dev.mikoto2000.rei.llm.ResolvedLlmEndpoint("fixture", change.equals("generation") ? 2 : 1,
        change.equals("api") ? dev.mikoto2000.rei.llm.LlmApiType.CHAT_COMPLETIONS : initial.apiType(),
        change.equals("url") ? "https://other.invalid" : initial.baseUrl(),
        change.equals("credential") ? "rotated-reference" : initial.credentialRef(),
        change.equals("model") ? "other-model" : initial.model(),
        change.equals("mode") ? new dev.mikoto2000.rei.llm.LlmCapabilities(
            dev.mikoto2000.rei.llm.ReasoningReplayMode.NONE, 128) : initial.capabilities(),
        initial.maxOutputTokens(), initial.temperature(), initial.readTimeout());
    var call = new AssistantMessage.ToolCall("call", "function", "fixture", "{}");
    var message = AssistantMessage.builder().content("working").toolCalls(List.of(call))
        .properties(Map.of("rei.llm.replay-origin-owner", "original-owner",
            "rei.llm.replay-origin-endpoint", initial.fingerprint())).build();
    var reference = payloads.save(scope, "run", "assistant", message);
    payloads.publish(scope, reference, dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.RUN, "run");
    var pending = new RunContinuation.PendingTools("working", List.of(call), List.of(), java.util.Set.of(), reference);
    var executions = new java.util.concurrent.atomic.AtomicInteger();
    var callback = new org.springframework.ai.tool.ToolCallback() {
      public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
        return org.springframework.ai.tool.definition.ToolDefinition.builder().name("fixture").description("fixture")
            .inputSchema("{}").build();
      }
      public String call(String input) { executions.incrementAndGet(); return "fixture"; }
    };
    var options = org.springframework.ai.model.tool.ToolCallingChatOptions.builder().toolCallbacks(callback)
        .toolContext(Map.of(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY,
            new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange(payloads, scope, "run", "resumed"),
            "rei.llm.resolved-endpoint", current,
            "rei.llm.replay-owner", change.equals("owner") ? "foreign-owner" : "original-owner")).build();
    Runnable resume = () -> new dev.mikoto2000.rei.core.chat.ToolLoopSupport().executePending(
        new org.springframework.ai.chat.prompt.Prompt("request", options), pending,
        org.mockito.Mockito.mock(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.class));
    boolean compatible = change.equals("same") || change.equals("credential");
    if (compatible) org.assertj.core.api.Assertions.assertThatCode(resume::run).doesNotThrowAnyException();
    else assertThatThrownBy(resume::run).isInstanceOf(IllegalStateException.class).hasMessage("Model message origin mismatch");
    assertThat(executions).hasValue(compatible ? 1 : 0);
  }

  @Test
  void resumedPrefixKeepsItsOriginalDeliveryPolicyAndReadGrant() {
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("delivery.db"));
    var owner = new AgentRunContext("run", "session", directory, "project");
    var scope = dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope.forOwner(owner);
    var payloads = new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore(source);
    var calls = List.of(new AssistantMessage.ToolCall("first", "function", "readMultiFile", "{}"),
        new AssistantMessage.ToolCall("second", "function", "fixture", "{}"));
    var assistant = payloads.save(scope, "run", "assistant",
        AssistantMessage.builder().content("working").toolCalls(calls).build());
    var prefix = org.springframework.ai.chat.messages.ToolResponseMessage.builder()
        .responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
            "first", "readMultiFile", "prefix-result")))
        .metadata(Map.of("rei.deliveryPolicies", Map.of("first",
            new dev.mikoto2000.rei.tooldelivery.ToolResultMetadata.Policy("FILE_CONTENT")),
            "rei.fileReadBudgets", Map.of("first",
                new dev.mikoto2000.rei.tooldelivery.ToolResultMetadata.FileReadBudget(80)))).build();
    var completed = payloads.save(scope, "run", "prefix", prefix);
    for (var ref : List.of(assistant, completed))
      payloads.publish(scope, ref, dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.RUN, "run");
    var pending = new RunContinuation.PendingTools("working", calls, prefix.getResponses(), java.util.Set.of(),
        assistant, completed);
    var store = new ApprovalContinuationStore(source);
    store.save("approval", owner, "request", List.of(new org.springframework.ai.chat.messages.UserMessage("request")),
        pending, new OutputLimitRunBudget(0, 5).snapshot(), false);
    var saved = new ApprovalContinuationStore(source).saved(store.find("approval").orElseThrow());
    var callbacks = new java.util.ArrayList<org.springframework.ai.tool.ToolCallback>();
    for (String name : List.of("readMultiFile", "fixture")) {
      var callback = org.mockito.Mockito.mock(org.springframework.ai.tool.ToolCallback.class);
      org.mockito.Mockito.when(callback.getToolDefinition()).thenReturn(
          org.springframework.ai.tool.definition.ToolDefinition.builder().name(name).description("fixture")
              .inputSchema("{}").build());
      org.mockito.Mockito.when(callback.getToolMetadata()).thenReturn(
          org.springframework.ai.tool.metadata.ToolMetadata.builder().build());
      if (name.equals("readMultiFile"))
        org.mockito.Mockito.when(callback.call(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
            .thenThrow(new AssertionError("Completed prefix must not execute again"));
      else
        org.mockito.Mockito.when(callback.call(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
            .thenReturn("approved-result");
      callbacks.add(callback);
    }
    var options = org.springframework.ai.model.tool.ToolCallingChatOptions.builder().toolCallbacks(callbacks)
        .toolContext(Map.of(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY,
            new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange(payloads, scope, "run", "resumed"))).build();
    var result = new dev.mikoto2000.rei.core.chat.ToolLoopSupport().executePending(
        new org.springframework.ai.chat.prompt.Prompt(saved.messages(), options), saved.pendingTools(),
        org.mockito.Mockito.mock(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.class));
    var delivery = dev.mikoto2000.rei.tooldelivery.ToolResultMetadata.capture(
        (org.springframework.ai.chat.messages.ToolResponseMessage) result.conversationHistory().getLast());
    assertThat(delivery.policies()).containsEntry("first", "FILE_CONTENT").containsEntry("second", "GENERAL");
    assertThat(delivery.fileReadBudgets().get("first").tokens()).isEqualTo(80);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void oversizedContinuationsDoNotPublishPayloadReferences(boolean oversizedHistory) {
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("capacity.db"));
    var owner = new AgentRunContext("run", "session", directory, "project");
    var message = AssistantMessage.builder().content("fixture")
        .properties(Map.of(dev.mikoto2000.rei.modelmessage.ModelTokenUsage.METADATA_KEY,
            new dev.mikoto2000.rei.modelmessage.ModelTokenUsage(1L, 1L, 2L, null, null))).build();
    var history = java.util.Collections.<org.springframework.ai.chat.messages.Message>nCopies(
        oversizedHistory ? 257 : 1, message);
    var calls = java.util.stream.IntStream.range(0, oversizedHistory ? 1 : 33)
        .mapToObj(index -> new AssistantMessage.ToolCall("call-" + index, "function", "fixture", "{}"))
        .toList();
    var pending = new RunContinuation.PendingTools("pending", calls, List.of());
    assertThatThrownBy(() -> new ApprovalContinuationStore(source).save("approval", owner, "request",
        history, pending, new OutputLimitRunBudget(0, 5).snapshot(), false))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("capacity");
    var usage = new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore(source).usage(
        dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope.forOwner(owner), "run");
    assertThat(usage.payloadCount()).isZero();
    assertThat(usage.referenceCount()).isZero();
  }

  @Test
  void completedPrefixAndOriginalPartsSurvivePendingApprovalRestart() throws Exception {
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("pending.db"));
    var owner = new AgentRunContext("run", "session", directory, "project");
    var payloads = new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore(source);
    var scope = dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope.forOwner(owner);
    var calls =
        List.of(
            new AssistantMessage.ToolCall("first", "function", "fixture", "{\"index\":1}"),
            new AssistantMessage.ToolCall("second", "function", "fixture", "{\"index\":2}"));
    var original =
        AssistantMessage.builder()
            .parts(
                List.of(
                    new ReasoningPart(
                        "",
                        "",
                        new OpaquePayload("fixture", "encrypted_content", "pending-opaque-one"),
                        Map.of("item_id", "reasoning-one")),
                    org.springframework.ai.chat.messages.part.ToolCallPart.of(calls.getFirst()),
                    new ReasoningPart(
                        "",
                        "",
                        new OpaquePayload("fixture", "encrypted_content", "pending-opaque-two"),
                        Map.of("item_id", "reasoning-two")),
                    org.springframework.ai.chat.messages.part.ToolCallPart.of(calls.getLast())))
            .build();
    var callback = org.mockito.Mockito.mock(org.springframework.ai.tool.ToolCallback.class);
    org.mockito.Mockito.when(callback.getToolDefinition())
        .thenReturn(
            org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name("fixture")
                .description("fixture")
                .inputSchema("{\"type\":\"object\"}")
                .build());
    org.mockito.Mockito.when(callback.getToolMetadata())
        .thenReturn(
            org.springframework.ai.tool.metadata.ToolMetadata.builder()
                .returnDirect(false)
                .build());
    var prefixCalls = new java.util.concurrent.atomic.AtomicInteger();
    org.mockito.Mockito.when(
            callback.call(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> {
          if (invocation.<String>getArgument(0).contains(":1")) {
            prefixCalls.incrementAndGet();
            return "saved prefix";
          }
          throw new ToolPermissionException("fixture", PermissionDecision.REQUIRE_APPROVAL)
              .withApprovalId("approval");
        });
    var context =
        org.mockito.Mockito.mock(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.class);
    org.mockito.Mockito.when(context.hasApprovalContinuation()).thenReturn(true);
    org.mockito.Mockito.when(
            context.suspendForApproval(
                org.mockito.ArgumentMatchers.eq("approval"),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> {
          new ApprovalContinuationStore(source)
              .save(
                  "approval",
                  owner,
                  "request",
                  invocation.getArgument(1),
                  invocation.getArgument(2),
                  new OutputLimitRunBudget(0, 5).snapshot(),
                  false);
          return true;
        });
    var options =
        org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
            .toolCallbacks(callback)
            .toolContext(
                Map.of(
                    dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY,
                    new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange(
                        payloads, scope, "run", "original")))
            .build();
    var prompt = new org.springframework.ai.chat.prompt.Prompt("request", options);
    var tools = new dev.mikoto2000.rei.core.chat.ToolLoopSupport();
    assertThatThrownBy(() ->
            tools.executeResumable(
                prompt,
                new org.springframework.ai.chat.model.ChatResponse(
                    List.of(new org.springframework.ai.chat.model.Generation(original))),
                context))
        .isInstanceOf(dev.mikoto2000.rei.core.chat.RunWaitingException.class);
    var restarted = new ApprovalContinuationStore(source);
    var entry = restarted.find("approval").orElseThrow();
    var saved = restarted.saved(entry);
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var broken = json.readTree(entry.document());
    ((com.fasterxml.jackson.databind.node.ObjectNode) broken.path("pending"))
        .remove("assistantReference");
    var missing =
        new ApprovalContinuationStore.Entry(
            entry.approvalId(),
            entry.runId(),
            entry.state(),
            json.writeValueAsString(broken),
            entry.pid(),
            entry.started());
    assertThatThrownBy(() -> restarted.saved(missing))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Invalid approval continuation")
        .hasNoCause();
    var missingResult = json.readTree(entry.document());
    ((com.fasterxml.jackson.databind.node.ObjectNode) missingResult.path("pending")).remove("completedReference");
    var missingPrefix = new ApprovalContinuationStore.Entry(entry.approvalId(), entry.runId(), entry.state(),
        json.writeValueAsString(missingResult), entry.pid(), entry.started());
    assertThatThrownBy(() -> restarted.saved(missingPrefix))
        .isInstanceOf(IllegalStateException.class).hasMessage("Invalid approval continuation").hasNoCause();
    org.mockito.Mockito.doReturn("approved result")
        .when(callback)
        .call(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    var resumedOptions =
        options
            .mutate()
            .toolContext(
                Map.of(
                    dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY,
                    new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange(
                        new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore(source),
                        scope,
                        "run",
                        "resumed")))
            .build();
    var result =
        tools.executePending(
            new org.springframework.ai.chat.prompt.Prompt(saved.messages(), resumedOptions),
            saved.pendingTools(),
            context);
    var restored = (AssistantMessage) result.conversationHistory().get(saved.messages().size());
    assertThat(restored.getParts()).containsExactlyElementsOf(original.getParts());
    assertThat(prefixCalls).hasValue(1);
    assertThat(
            ((org.springframework.ai.chat.messages.ToolResponseMessage)
                    result.conversationHistory().getLast())
                .getResponses())
        .extracting(
            org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse::responseData)
        .containsExactly("saved prefix", "approved result");
    assertThat(entry.document()).doesNotContain("pending-opaque");
    assertThat(entry.document()).doesNotContain("saved prefix");
  }

  @Test
  void restartRestoresExactProtocolHistoryWithoutInliningOpaqueInContinuation() throws Exception {
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("approval.db"));
    var owner = new AgentRunContext("run", "session", directory, "project");
    var original =
        AssistantMessage.builder()
            .parts(
                List.of(
                    new ReasoningPart(
                        "",
                        "",
                        new OpaquePayload("fixture", "encrypted_content", "opaque-fixture+/="),
                        Map.of("item_id", "reasoning-item", "status", "completed")),
                    TextPart.of("completed prefix")))
            .properties(
                Map.of(
                    "rei.llm.replay-origin-owner",
                    "session",
                    "rei.llm.replay-origin-endpoint",
                    "fingerprint"))
            .build();
    var pending =
        new RunContinuation.PendingTools(
            "pending",
            List.of(new AssistantMessage.ToolCall("call", "function", "fixture", "{}")),
            List.of());
    new ApprovalContinuationStore(source)
        .save(
            "approval",
            owner,
            "request",
            List.of(original),
            pending,
            new OutputLimitRunBudget(0, 5).snapshot(),
            false);
    var restarted = new ApprovalContinuationStore(source);
    var entry = restarted.find("approval").orElseThrow();
    var restored = (AssistantMessage) restarted.saved(entry).messages().getFirst();
    assertThat(restored.getParts()).containsExactlyElementsOf(original.getParts());
    assertThat(restored.getMetadata()).containsAllEntriesOf(original.getMetadata());
    assertThat(entry.document()).doesNotContain("opaque-fixture");
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var document = json.readTree(entry.document());
    ((com.fasterxml.jackson.databind.node.ObjectNode) document.path("history").get(0))
        .remove("payload");
    var missing =
        new ApprovalContinuationStore.Entry(
            entry.approvalId(),
            entry.runId(),
            entry.state(),
            json.writeValueAsString(document),
            entry.pid(),
            entry.started());
    assertThatThrownBy(() -> restarted.saved(missing)).isInstanceOf(IllegalStateException.class);
  }
}
