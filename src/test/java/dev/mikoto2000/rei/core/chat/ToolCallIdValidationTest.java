package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class ToolCallIdValidationTest {
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  ", "first"})
  void rejectsInvalidIdsBeforeAnyCallbackAndAcceptsCorrectedBatch(String invalidId) {
    var calls = new AtomicInteger();
    var callback =
        new ToolCallback() {
          @Override
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("sample")
                .description("Sample")
                .inputSchema("{}")
                .build();
          }

          @Override
          public String call(String arguments) {
            calls.incrementAndGet();
            return "done";
          }
        };
    var options = OpenAiChatOptions.builder().toolCallbacks(List.of(callback)).build();
    var prompt = new Prompt(new UserMessage("Run the tools."), options);
    var tools = new ToolLoopSupport();

    var rejected = tools.execute(prompt, batch("first", invalidId));

    assertThat(calls).hasValue(0);
    assertThat(rejected.conversationHistory().getLast().getText())
        .contains("No calls", "distinct, nonblank");
    tools.execute(new Prompt(rejected.conversationHistory(), options), batch("first", "second"));
    assertThat(calls).hasValue(2);
  }

  private static ChatResponse batch(String first, String second) {
    var output =
        AssistantMessage.builder()
            .content("")
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall(first, "function", "sample", "{}"),
                    new AssistantMessage.ToolCall(second, "function", "sample", "{}")))
            .build();
    return new ChatResponse(List.of(new Generation(output)));
  }
}
