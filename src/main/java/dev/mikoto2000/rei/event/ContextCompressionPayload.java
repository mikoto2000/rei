package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡す文脈圧縮通知内容を表す。
 *
 * @param beforeEstimatedTokens 変更前推定トークン数
 * @param afterEstimatedTokens 変更後推定トークン数
 * @param compressedMessageCount 圧縮済みメッセージ件数
 * @param summaryThroughSequence 要約終点発生順序
 * @param reason 理由
 * @param details 詳細一覧
 */
public record ContextCompressionPayload(
    long beforeEstimatedTokens,
    long afterEstimatedTokens,
    int compressedMessageCount,
    long summaryThroughSequence,
    String reason,
    String details)
    implements AgentEventPayload {
  /** 実行状況の通知を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ContextCompressionPayload(
      long beforeEstimatedTokens,
      long afterEstimatedTokens,
      int compressedMessageCount,
      long summaryThroughSequence,
      String reason) {
    this(
        beforeEstimatedTokens,
        afterEstimatedTokens,
        compressedMessageCount,
        summaryThroughSequence,
        reason,
        null);
  }
}
