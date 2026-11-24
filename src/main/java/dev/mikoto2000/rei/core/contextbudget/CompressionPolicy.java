package dev.mikoto2000.rei.core.contextbudget;

import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * 圧縮の判断基準をまとめる。
 *
 * <p>Selects a contiguous old prefix; tool calls and their responses form indivisible groups.
 */
public final class CompressionPolicy {
  /** 概算。 */
  private final TokenEstimator estimator;

  /** 指定された値や利用先を使って、CompressionPolicyを初期化する。 */
  public CompressionPolicy(TokenEstimator estimator) {
    this.estimator = estimator;
  }

  /** モデルへ渡す文脈の管理で用いる接頭辞終点圧縮を返す。 */
  public int prefixToCompress(List<Message> messages, int recentTokens) {
    return prefixToCompress(messages, recentTokens, false);
  }

  int prefixToCompress(List<Message> messages, int recentTokens, boolean completedHistory) {
    long tokens = 0;
    int cut = messages.size();
    while (cut > 0) {
      int next = estimator.message(messages.get(cut - 1));
      if (cut < messages.size() && tokens + next > recentTokens) break;
      tokens += next;
      cut--;
    }
    int safe = 0;
    var boundaries = completePrefixes(messages);
    for (int boundary : boundaries) {
      if (boundary > cut) break;
      safe = boundary;
    }
    // A completed historical turn can be larger than the recent target by itself. Summarize
    // that whole turn rather than separating its request or making it impossible to compress.
    if (safe == 0 && cut > 0 && completedHistory && !boundaries.isEmpty()
        && boundaries.getLast() == messages.size()) return messages.size();
    return safe;
  }
  /** Complete protocol prefixes, shared by summarization and hard-limit trimming. */
  static List<Integer> completePrefixes(List<Message> messages) {
    var boundaries = new java.util.ArrayList<Integer>();
    var pending = new java.util.HashMap<String, String>();
    boolean waitingForAssistant = false;
    for (int i = 0; i < messages.size(); i++) {
      var message = messages.get(i);
      if (message instanceof org.springframework.ai.chat.messages.UserMessage) {
        if (!pending.isEmpty()) return boundaries;
        waitingForAssistant = true;
      } else if (message instanceof org.springframework.ai.chat.messages.AssistantMessage assistant) {
        if (!pending.isEmpty()) return boundaries;
        for (var call : assistant.getToolCalls()) {
          if (call.id() == null || call.id().isBlank() || call.name() == null || call.name().isBlank()
              || pending.putIfAbsent(call.id(), call.name()) != null) return List.of();
        }
        waitingForAssistant = false;
        if (pending.isEmpty()) boundaries.add(i + 1);
      } else if (message instanceof ToolResponseMessage tool) {
        if (pending.isEmpty() || tool.getResponses().isEmpty()) return List.of();
        for (var result : tool.getResponses()) {
          if (result.id() == null || result.name() == null || !result.name().equals(pending.remove(result.id())))
            return List.of();
        }
        if (pending.isEmpty() && !waitingForAssistant) boundaries.add(i + 1);
      }
    }
    return boundaries;
  }

}
