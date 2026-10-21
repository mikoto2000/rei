package dev.mikoto2000.rei.conversation;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 会話履歴と受付記録の管理で受け渡す会話ログ項目を表す。
 *
 * @param conversationId 会話の識別子
 * @param scope 対象範囲
 * @param speaker 読み上げ先
 * @param timestamp 記録日時
 * @param content 内容
 * @param sequence 発生順序
 * @param source 取得元
 * @param sourceId 取得元識別子
 * @param metadata 付随情報
 */
public record ConversationLogEntry(
    String conversationId,
    String scope,
    String speaker,
    OffsetDateTime timestamp,
    String content,
    long sequence,
    String source,
    String sourceId,
    Map<String, String> metadata) {
  /** 受け取った情報からConversationLogEntryを作成する。 */
  public ConversationLogEntry {
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }

  /** 会話履歴と受付記録の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ConversationLogEntry(
      String conversationId,
      String scope,
      String speaker,
      OffsetDateTime timestamp,
      String content,
      long sequence) {
    this(conversationId, scope, speaker, timestamp, content, sequence, null, null, Map.of());
  }

  /** 会話履歴と受付記録の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ConversationLogEntry(
      String conversationId,
      String scope,
      String speaker,
      OffsetDateTime timestamp,
      String content) {
    this(conversationId, scope, speaker, timestamp, content, 0);
  }
}
