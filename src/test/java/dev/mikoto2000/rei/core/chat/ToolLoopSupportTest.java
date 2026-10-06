package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class ToolLoopSupportTest {
  @Test void rejectsDefaultCallbacks() {
    ToolCallback callback = new ToolCallback() {
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name("ambient").description("ambient").inputSchema("{}").build();
      }
      public String call(String input) { throw new AssertionError("must not execute"); }
    };
    assertThatThrownBy(() -> ToolLoopSupport.requireNoDefaultTools(model(
        OpenAiChatOptions.builder().toolCallbacks(callback).build())))
        .isInstanceOf(IllegalArgumentException.class);
  }
  @Test void rejectsRawToolsInOfficialSdkExtraBody() {
    var options = OpenAiChatOptions.builder().extraBody(Map.of("tools", List.of(
        Map.of("type", "function", "function", Map.of("name", "ambient"))))).build();
    assertThatThrownBy(() -> ToolLoopSupport.requireNoDefaultTools(model(options)))
        .isInstanceOf(IllegalArgumentException.class);
  }
  @Test void acceptsAbsentAndEmptyToolCollections() {
    assertThatCode(() -> ToolLoopSupport.requireNoDefaultTools(model(OpenAiChatOptions.builder().build())))
        .doesNotThrowAnyException();
    assertThatCode(() -> ToolLoopSupport.requireNoDefaultTools(model(OpenAiChatOptions.builder()
        .toolCallbacks(List.of()).extraBody(Map.of("tools", List.of(), "custom", "value")).build())))
        .doesNotThrowAnyException();
  }
  @Test void existingRunBudgetOwnsPerToolLimit() {
    executeAfterPriorResponses(40, 1);
  }
  @Test void existingRunBudgetOwnsTotalToolLimit() {
    executeAfterPriorResponses(150, 5);
  }
  private void executeAfterPriorResponses(int priorCalls, int names) {
    var invocations = new java.util.concurrent.atomic.AtomicInteger();
    ToolCallback callback = new ToolCallback() {
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name("tool0").description("test").inputSchema("{}").build();
      }
      public String call(String input) { invocations.incrementAndGet(); return "done"; }
    };
    var messages = new java.util.ArrayList<org.springframework.ai.chat.messages.Message>();
    messages.add(new org.springframework.ai.chat.messages.UserMessage("bounded application run"));
    for (int i = 0; i < priorCalls; i++) {
      messages.add(org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(List.of(
          new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("old-" + i,
              "tool" + (i % names), "ok"))).build());
    }
    var prompt = new Prompt(messages, OpenAiChatOptions.builder().toolCallbacks(callback).build());
    var response = new ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
        org.springframework.ai.chat.messages.AssistantMessage.builder().content("").toolCalls(List.of(
            new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("new", "function", "tool0", "{}")))
            .build())));
    new ToolLoopSupport().execute(prompt, response);
    assertThat(invocations.get()).isEqualTo(1);
  }

  private ChatModel model(ChatOptions options) {
    return new ChatModel() {
      public ChatOptions getOptions() { return options; }
      public ChatResponse call(Prompt prompt) { throw new AssertionError("must not call model"); }
    };
  }
}
