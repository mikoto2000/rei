package dev.mikoto2000.rei.topic;

/** 話題の管理における情報への関心状態の選択肢。 */
public enum CuriosityStatus {
  /** 待機中。 */
  PENDING,
  /** 使用量。 */
  USED,
  /** 利用者が不要と判断した関心。 */
  DISMISSED,
  /** 期限切れ。 */
  EXPIRED
}
