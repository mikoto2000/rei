package dev.mikoto2000.rei.core.contextbudget;

import java.time.Instant;

/**
 * モデルへ渡す文脈の管理で受け渡す会話要約を表す。
 *
 * <p>Cursor is scoped to its conversation (turn sequence) or run (message sequence), never an event
 * bus sequence.
 *
 * @param summary 要約
 * @param throughSequence 終点発生順序
 * @param updatedAt 更新日時
 */
public record ConversationSummary(String summary, long throughSequence, Instant updatedAt) {
  /** 対象がない場合の結果を表す。 */
  public static ConversationSummary empty() {
    return new ConversationSummary("", 0, Instant.EPOCH);
  }
}
