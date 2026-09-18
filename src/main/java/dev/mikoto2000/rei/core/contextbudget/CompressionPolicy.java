package dev.mikoto2000.rei.core.contextbudget;

import java.util.List;
import org.springframework.ai.chat.messages.*;

/** Selects a contiguous old prefix; tool calls and their responses form indivisible groups. */
public final class CompressionPolicy {
  private final TokenEstimator estimator;
  public CompressionPolicy(TokenEstimator estimator) { this.estimator = estimator; }
  public int prefixToCompress(List<Message> messages, int recentTokens) {
    long tokens = 0;
    int cut = messages.size();
    while (cut > 0) {
      int next = estimator.message(messages.get(cut - 1));
      if (cut < messages.size() && tokens + next > recentTokens) break;
      tokens += next;
      cut--;
    }
    while (cut > 0 && cut < messages.size() && messages.get(cut) instanceof ToolResponseMessage) cut--;
    return cut;
  }
}
