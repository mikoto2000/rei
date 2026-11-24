package dev.mikoto2000.rei.core.contextbudget;

import java.util.List;
import org.springframework.ai.chat.messages.Message;

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
    if (safe == 0
        && cut > 0
        && completedHistory
        && !boundaries.isEmpty()
        && boundaries.getLast() == messages.size()) return messages.size();
    return safe;
  }

  /** Complete protocol prefixes, shared with native checkpoint recovery. */
  static List<Integer> completePrefixes(List<Message> messages) {
    return dev.mikoto2000.rei.modelmessage.ModelMessageTransactions.completePrefixes(messages);
  }
}
