package dev.mikoto2000.rei.topic;

import java.time.Instant;

/**
 * 話題の管理で受け渡す情報への関心項目を表す。
 *
 * @param id 識別子
 * @param question 質問
 * @param reason 理由
 * @param source 取得元
 * @param priority 優先度
 * @param createdAt 作成日時
 * @param expiresAt 有効期限
 * @param status 状態
 */
public record CuriosityItem(
    String id,
    String question,
    String reason,
    TopicSource source,
    double priority,
    Instant createdAt,
    Instant expiresAt,
    CuriosityStatus status) {

  /** 受け取った情報からCuriosityItemを作成する。 */
  public CuriosityItem {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
    if (question == null || question.isBlank())
      throw new IllegalArgumentException("question must not be blank");
    if (reason == null) reason = "";
    if (source == null) source = TopicSource.CONVERSATION;
    priority = Math.max(0.0d, Math.min(1.0d, priority));
    if (createdAt == null) throw new IllegalArgumentException("createdAt must not be null");
    if (status == null) status = CuriosityStatus.PENDING;
  }

  /** 指定された状態を反映した情報を作成する。 */
  public CuriosityItem withStatus(CuriosityStatus newStatus) {
    return new CuriosityItem(
        id, question, reason, source, priority, createdAt, expiresAt, newStatus);
  }
}
