package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** The existing dispatch loop must preserve the model's ordered protocol parts. */
class ToolLoopPartsTest {
  @Test
  void inheritedReadCursorKeepsPartOrderItemIdsAndReasoning() {
    var seen = new java.util.concurrent.atomic.AtomicReference<String>();
    var callback = new ToolCallback() {
      @Override public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name("readMultiFile").description("fixture")
            .inputSchema("{\"type\":\"object\"}").build();
      }
      @Override public String call(String input) { seen.set(input); return "[]"; }
    };
    String version = "a".repeat(64);
    var page = ToolResponseMessage.builder().responses(List.of(
        new ToolResponseMessage.ToolResponse("previous", "readMultiFile", """
            [{"path":"a.md","version":"%s","truncated":true,
              "nextRead":{"path":"a.md","startLine":117,"offset":48,"expectedVersion":"%s"}}]
            """.formatted(version, version))))
        .metadata(Map.of("rei.deliveryPolicies", Map.of("previous",
            dev.mikoto2000.rei.core.contextbudget.ResultDelivery.Kind.FILE_CONTENT))).build();
    var reasoning = new ReasoningPart(null, null,
        new OpaquePayload("openai", "encrypted_content", "opaque"), Map.of("itemId", "rs_one"));
    var call = new ToolCallPart(new AssistantMessage.ToolCall("call_read", "function", "readMultiFile",
        "{\"files\":[{\"path\":\"a.md\",\"startLine\":117}]}"), null,
        Map.of("itemId", "fc_read", "itemStatus", "completed"));
    var text = new TextPart("reading", null, Map.of("itemId", "msg_one", "phase", "commentary"));
    var assistant = AssistantMessage.builder().parts(List.of(reasoning, call, text))
        .properties(Map.of("fixture-origin", "origin")).build();
    var prompt = new Prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("fixture"), page),
        OpenAiChatOptions.builder().toolCallbacks(callback).build());
    var result = new ToolLoopSupport().execute(prompt, new ChatResponse(List.of(new Generation(assistant))));
    assertThat(seen.get()).contains("\"offset\":48", "\"expectedVersion\":\"" + version + "\"");
    var saved = (AssistantMessage) result.conversationHistory().get(2);
    assertThat(saved.getParts()).hasSize(3);
    assertThat(saved.getParts().get(0)).isEqualTo(reasoning);
    assertThat(saved.getParts().get(2)).isEqualTo(text);
    assertThat(saved.getParts().get(1).attributes()).isEqualTo(call.attributes());
    assertThat(saved.getMetadata()).containsEntry("fixture-origin", "origin");
    assertThat(assistant.getParts()).containsExactly(reasoning, call, text);
  }

  @Test
  void ordinaryDispatchPreservesInterleavedPartsAndOpaqueAttributes() {
    var executions = new AtomicInteger();
    var callback = new ToolCallback() {
      @Override public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name("fixture_tool").description("fixture")
            .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
      }
      @Override public String call(String input) { executions.incrementAndGet(); return "fixture result"; }
    };
    var first = new AssistantMessage.ToolCall("call_one", "function", "fixture_tool", "{}");
    var second = new AssistantMessage.ToolCall("call_two", "function", "fixture_tool", "{}");
    var assistant = AssistantMessage.builder().parts(List.of(
        new ReasoningPart(null, null, new OpaquePayload("openai", "encrypted_content", "opaque-one"),
            Map.of("itemId", "rs_one")),
        new ToolCallPart(first, null, Map.of("itemId", "fc_one", "itemStatus", "completed")),
        new TextPart("working", null, Map.of("itemId", "msg_one", "phase", "commentary")),
        new ReasoningPart(null, null, new OpaquePayload("openai", "encrypted_content", "opaque-two"),
            Map.of("itemId", "rs_two")),
        new ToolCallPart(second, null, Map.of("itemId", "fc_two", "itemStatus", "completed"))))
        .build();
    var prompt = new Prompt("fixture", OpenAiChatOptions.builder().toolCallbacks(callback).build());
    var result = new ToolLoopSupport().execute(prompt, new ChatResponse(List.of(new Generation(assistant))));
    assertThat(executions.get()).isEqualTo(2);
    var saved = (AssistantMessage) result.conversationHistory().get(1);
    assertThat(saved.getParts()).containsExactlyElementsOf(assistant.getParts());
    var results = (ToolResponseMessage) result.conversationHistory().getLast();
    assertThat(results.getResponses()).extracting(ToolResponseMessage.ToolResponse::id)
        .containsExactly("call_one", "call_two");
  }
}
