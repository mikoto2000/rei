package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;

class CompressionPolicyTest {
  final TokenEstimator estimator = TokenEstimator.conservative();
  @Test void countsJapaneseAndToolArgumentsRatherThanOnlyMessageText() {
    assertThat(estimator.text("日本語")).isGreaterThanOrEqualTo(3);
    var call = AssistantMessage.builder().content("").toolCalls(List.of(
        new AssistantMessage.ToolCall("id", "function", "readFile", "x".repeat(400)))).build();
    assertThat(estimator.message(call)).isGreaterThanOrEqualTo(100);
  }
  @Test void retainsRecentBudgetAndNeverSplitsToolPairs() {
    var call = AssistantMessage.builder().content("").toolCalls(List.of(
        new AssistantMessage.ToolCall("id", "function", "readFile", "{}"))).build();
    var result = ToolResponseMessage.builder().responses(List.of(
        new ToolResponseMessage.ToolResponse("id", "readFile", "x".repeat(200)))).build();
    List<Message> messages = List.of(new UserMessage("old".repeat(300)), call, result);
    assertThat(new CompressionPolicy(estimator).prefixToCompress(messages, 40)).isEqualTo(1);
  }
  @Test void protectedSectionsGetBudgetBeforeOptionalOnes() {
    var budget = new ContextBudgetManager(100, 10, 10);
    var result = budget.allocate(List.of(new ContextSection("TOOL_RESULTS", "x".repeat(240)),
        new ContextSection("SYSTEM", "x".repeat(200)), new ContextSection("CURRENT_USER", "x".repeat(40))));
    assertThat(result.included()).containsExactly("SYSTEM", "CURRENT_USER");
    assertThat(result.dropped()).containsExactly("TOOL_RESULTS");
  }
}
