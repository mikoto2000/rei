package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.session.SessionMetadata;

/**
 * Web APIによる操作で受け渡す会話応答を表す。
 *
 * @param sessionId 会話の識別子
 * @param projectId 所属プロジェクトの識別子
 * @param title タイトル
 * @param createdAt 作成日時
 * @param updatedAt 更新日時
 */
public record SessionResponse(
    String sessionId, String projectId, String title, String createdAt, String updatedAt) {
  /** Web APIによる操作で利用する情報を、受け取った形式から変換する。 */
  static SessionResponse from(SessionMetadata row) {
    return new SessionResponse(
        row.sessionId(),
        row.projectId(),
        row.title(),
        row.createdAt().toString(),
        row.updatedAt().toString());
  }
}
