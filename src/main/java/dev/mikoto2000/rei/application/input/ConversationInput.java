package dev.mikoto2000.rei.application.input;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 会話入力の受付で受け渡す会話入力を表す。
 *
 * <p>Modality metadata never interprets the text as a CLI command.
 *
 * @param inputId 入力識別子
 * @param source 取得元
 * @param target 対象
 * @param text テキスト
 * @param createdAt 作成日時
 */
public record ConversationInput(
    UUID inputId, InputSource source, ConversationTarget target, String text, Instant createdAt) {
  /** 受け取った情報からConversationInputを作成する。 */
  public ConversationInput {
    Objects.requireNonNull(inputId);
    Objects.requireNonNull(source);
    Objects.requireNonNull(target);
    Objects.requireNonNull(createdAt);
    if (text == null || text.isBlank()) throw new IllegalArgumentException("Message required");
    if (source == InputSource.VOICE
        && (target.sessionId() == null || text.stripLeading().startsWith("/")))
      throw new IllegalArgumentException(
          "Voice requires a captured Session and cannot execute slash commands");
  }
}
