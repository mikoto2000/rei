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
    long tokens = 0;
    int cut = messages.size();
    while (cut > 0) {
      int next = estimator.message(messages.get(cut - 1));
      if (cut < messages.size() && tokens + next > recentTokens) break;
      tokens += next;
      cut--;
    }
    while (cut > 0 && cut < messages.size() && messages.get(cut) instanceof ToolResponseMessage)
      cut--;
    return cut;
  }
}
