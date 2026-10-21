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

/** 補助エージェントモデル既定値の結果と状態の契約を検証するテスト。 */
class SubAgentModelDefaultsTest {
  /** 補助エージェントへの委譲の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void ambientCallbackAuthorityIsRejectedBeforeCallingTheModel() {
    ChatModel unsafe =
        new ChatModel() {
          /** 検証用に、補助エージェントへの委譲の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt prompt) {
            throw new AssertionError();
          }

          /** 検証用に、補助エージェントへの委譲で参照する選択可能な設定を返す。 */
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
