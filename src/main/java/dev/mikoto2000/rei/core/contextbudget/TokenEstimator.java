package dev.mikoto2000.rei.core.contextbudget;

import org.springframework.ai.chat.messages.*;

/** Replaceable estimate, not an exact tokenizer. Non-ASCII text is deliberately more conservative. */
public interface TokenEstimator {
  int text(String text);
  default int message(Message message) {
    long total = 8L + text(message.getText());
    if (message instanceof AssistantMessage assistant) for (var call : assistant.getToolCalls())
      total += 12L + text(call.id()) + text(call.name()) + text(call.arguments());
    if (message instanceof ToolResponseMessage tool) for (var result : tool.getResponses())
      total += 12L + text(result.id()) + text(result.name()) + text(result.responseData());
    if (message instanceof UserMessage user) total += 4096L * user.getMedia().size();
    return (int) Math.min(Integer.MAX_VALUE, total);
  }
  static TokenEstimator conservative() {
    return value -> {
      if (value == null || value.isEmpty()) return 0;
      long units = value.codePoints().mapToLong(c -> c < 128 ? 1 : 8).sum();
      return (int) Math.min(Integer.MAX_VALUE, (units + 3) / 4);
    };
  }
}
