package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.session.SessionTurn;
import java.util.Map;

/**
 * Web APIによる操作で受け渡す会話一回のやり取り応答を表す。
 *
 * @param turnId やり取りの識別子
 * @param runId 実行の識別子
 * @param userMessage 利用者メッセージ
 * @param assistantMessage アシスタントメッセージ
 * @param createdAt 作成日時
 * @param source 取得元
 * @param sourceId 取得元識別子
 * @param metadata 付随情報
 */
public record SessionTurnResponse(
    String turnId,
    String runId,
    String userMessage,
    String assistantMessage,
    String createdAt,
    String source,
    String sourceId,
    Map<String, String> metadata) {
  /** Web APIによる操作で利用する情報を、受け取った形式から変換する。 */
  static SessionTurnResponse from(SessionTurn row) {
    return new SessionTurnResponse(
        row.runId(),
        row.runId(),
        row.userMessage(),
        row.assistantMessage(),
        row.createdAt().toString(),
        row.source(),
        row.sourceId(),
        row.metadata());
  }
}
