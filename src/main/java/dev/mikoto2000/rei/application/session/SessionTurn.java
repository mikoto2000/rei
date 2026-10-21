package dev.mikoto2000.rei.application.session;

import java.time.Instant;
import java.util.Map;

/**
 * 会話の継続管理で受け渡す会話一回のやり取りを表す。
 *
 * @param runId 実行の識別子
 * @param userMessage 利用者メッセージ
 * @param assistantMessage アシスタントメッセージ
 * @param createdAt 作成日時
 * @param source 取得元
 * @param sourceId 取得元識別子
 * @param metadata 付随情報
 */
public record SessionTurn(
    String runId,
    String userMessage,
    String assistantMessage,
    Instant createdAt,
    String source,
    String sourceId,
    Map<String, String> metadata) {
  /** 会話の継続管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SessionTurn(String runId, String userMessage, String assistantMessage, Instant createdAt) {
    this(runId, userMessage, assistantMessage, createdAt, null, null, Map.of());
  }
}
