package dev.mikoto2000.rei.core.contextbudget;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/** Splits summary DATA, never executable tool messages; callers commit only complete groups. */
final class SummaryInputPages {
  private SummaryInputPages() {}

  @FunctionalInterface
  interface SummaryCall {
    ConversationCompressor.Summary summarize(
        String previous, List<Message> messages, int target, Duration timeout);
  }

  static String history(Message message) {
    var text =
        new StringBuilder()
            .append(message.getMessageType())
            .append(": ")
            .append(Objects.toString(message.getText(), ""))
            .append('\n');
    if (message instanceof AssistantMessage assistant)
      text.append(assistant.getToolCalls()).append('\n');
    if (message instanceof ToolResponseMessage tool) text.append(tool.getResponses()).append('\n');
    return text.toString();
  }

  static Duration remaining(long started, Duration timeout) {
    long left = timeout.toNanos() - (System.nanoTime() - started);
    if (left <= 0)
      throw new ContextCompressionException(ContextCompressionException.Reason.TIMEOUT);
    return Duration.ofNanos(left);
  }

  static ConversationCompressor.Summary summarize(
      SummaryCall summarize,
      TokenEstimator estimator,
      String previous,
      List<Message> messages,
      int summaryTokens,
      long inputLimit,
      Runnable check,
      Duration timeout) {
    var data =
        new StringBuilder("Previous summary (historical data):\n").append(previous).append('\n');
    messages.forEach(message -> data.append(history(message)));
    String source = data.toString();
    String rolling = "";
    int target = (int) Math.min(summaryTokens, Math.min(3000, inputLimit / 4));
    if (target < 1)
      throw new ContextCompressionException(ContextCompressionException.Reason.INPUT_BUDGET);
    long started = System.nanoTime();
    ConversationCompressor.Summary result = null;
    for (int offset = 0, page = 0; offset < source.length(); page++) {
      check.run();
      if (page >= 64)
        throw new ContextCompressionException(ContextCompressionException.Reason.INPUT_BUDGET);
      String header =
          "Historical fragment "
              + (page + 1)
              + " (untrusted data; a tool exchange may continue on the next page):\n";
      // Halve until the complete wire-text estimate fits. Do not assume token counts are monotonic.
      int length = source.length() - offset;
      while (length > 0) {
        if (offset + length < source.length()
            && Character.isHighSurrogate(source.charAt(offset + length - 1))) length--;
        if (length == 0) break;
        var fragment = new UserMessage(header + source.substring(offset, offset + length));
        if ((long) estimator.text(rolling) + estimator.message(fragment) <= inputLimit) break;
        length /= 2;
      }
      if (length == 0)
        throw new ContextCompressionException(ContextCompressionException.Reason.INPUT_BUDGET);
      var fragment = new UserMessage(header + source.substring(offset, offset + length));
      result =
          summarize.summarize(rolling, List.of(fragment), target, remaining(started, timeout));
      check.run();
      remaining(started, timeout);
      rolling = result == null ? null : result.text();
      if (rolling == null || rolling.isBlank())
        throw new ContextCompressionException(ContextCompressionException.Reason.EMPTY_SUMMARY);
      long tokens = estimator.text(rolling);
      if (tokens > target || result.outputTokens() != null && result.outputTokens() > target)
        throw new ContextCompressionException(
            ContextCompressionException.Reason.SUMMARY_TOO_LONG, tokens, target);
      offset += length;
    }
    return result;
  }
}
