package dev.mikoto2000.rei.voice;

import java.util.Optional;

/** 音声による対話に必要な発話結果絞り込み条件を扱う。 */
public final class SpeechResultFilter {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private SpeechResultFilter() {}

  /** 音声による対話で用いる絞り込み条件を返す。 */
  public static Optional<String> filter(String text) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (text == null || text.length() > 16384) return Optional.empty();
    String cleaned = text.strip();
    if (cleaned.isEmpty()
        || cleaned.startsWith("/")
        || cleaned.codePoints().noneMatch(Character::isLetterOrDigit)
        || cleaned
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) && !Character.isWhitespace(c))) {
      return Optional.empty();
    }
    return Optional.of(cleaned);
  }
}
