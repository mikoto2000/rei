package dev.mikoto2000.rei.application.session;

import dev.mikoto2000.rei.core.chat.ResponseStyle;
import java.time.Instant;
import java.util.Objects;

/**
 * 会話の継続管理で受け渡す会話付随情報を表す。
 *
 * @param sessionId 会話の識別子
 * @param projectId 所属プロジェクトの識別子
 * @param title タイトル
 * @param createdAt 作成日時
 * @param updatedAt 更新日時
 * @param responseStyle 会話の応答形式
 * @param voiceOnly 音声入力だけに適用するかどうか
 */
public record SessionMetadata(
    String sessionId,
    String projectId,
    String title,
    Instant createdAt,
    Instant updatedAt,
    ResponseStyle responseStyle,
    boolean voiceOnly) {
  /** 会話の継続管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SessionMetadata(
      String sessionId, String projectId, String title, Instant createdAt, Instant updatedAt) {
    this(sessionId, projectId, title, createdAt, updatedAt, ResponseStyle.AUTO, false);
  }

  /** 受け取った情報からSessionMetadataを作成する。 */
  public SessionMetadata {
    if (responseStyle == null) responseStyle = ResponseStyle.AUTO;
    if (responseStyle != ResponseStyle.CONVERSATION) voiceOnly = false;
    Objects.requireNonNull(sessionId);
    Objects.requireNonNull(projectId);
    Objects.requireNonNull(title);
    Objects.requireNonNull(createdAt);
    Objects.requireNonNull(updatedAt);
  }

  /** 指定された会話の応答形式を反映した情報を作成する。 */
  public SessionMetadata withResponseStyle(ResponseStyle style, boolean onlyVoice, Instant time) {
    Objects.requireNonNull(style);
    return new SessionMetadata(
        sessionId,
        projectId,
        title,
        createdAt,
        time.isAfter(updatedAt) ? time : updatedAt,
        style,
        onlyVoice);
  }

  /** 会話の継続管理で用いるtouchedを返す。 */
  public SessionMetadata touched(Instant time) {
    return new SessionMetadata(
        sessionId,
        projectId,
        title,
        createdAt,
        time.isAfter(updatedAt) ? time : updatedAt,
        responseStyle,
        voiceOnly);
  }
}
