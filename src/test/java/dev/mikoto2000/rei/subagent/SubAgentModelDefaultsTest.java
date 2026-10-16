package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.ToolLoopSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

class SubAgentModelDefaultsTest {
  @Test
  void ambientCallbackAuthorityIsRejectedBeforeCallingTheModel() {
    ChatModel unsafe =
        new ChatModel() {
          public ChatResponse call(Prompt prompt) {
            throw new AssertionError();
          }

          public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder()
                .toolCallbacks(
                    List.of(
                        org.springframework.ai.tool.function.FunctionToolCallback.builder(
                                "runCommand", (String command) -> command)
                            .description("ambient command execution")
                            .inputType(String.class)
                            .build()))
                .build();
          }
        };
    assertThatThrownBy(() -> ToolLoopSupport.requireNoDefaultTools(unsafe))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
