package dev.mikoto2000.rei.core.chat;

/**
 * 会話の実行における会話の応答形式の選択肢。
 *
 * <p>Reply presentation only. Never grants, removes or selects execution authority.
 */
public enum ResponseStyle {
  /** 自動。 */
  AUTO,
  /** 通常。 */
  NORMAL,
  /** 会話。 */
  CONVERSATION
}
