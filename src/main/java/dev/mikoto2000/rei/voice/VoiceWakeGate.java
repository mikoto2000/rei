package dev.mikoto2000.rei.voice;

import dev.mikoto2000.rei.application.input.ConversationTarget;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 音声による対話に必要な音声待機の解除実行可否の判断を扱う。
 *
 * <p>Post-ASR wake prefix gate. It still requires ASR and is not speaker identification.
 */
public final class VoiceWakeGate {
  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** armed対象。 */
  private ConversationTarget armedTarget;

  /** armed終了日時。 */
  private Instant armedUntil;

  /** 指定された値や利用先を使って、VoiceWakeGateを初期化する。 */
  public VoiceWakeGate(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  /** 新たな処理を開始できるよう、音声待機の解除実行可否の判断を初期状態へ戻す。 */
  public synchronized void reset() {
    armedTarget = null;
    armedUntil = null;
  }

  /** 音声による対話で用いる絞り込み条件を返す。 */
  public synchronized Optional<String> filter(
      String text, ConversationTarget target, VoiceAdvancedOptions options) {
    Objects.requireNonNull(text);
    Objects.requireNonNull(target);
    Objects.requireNonNull(options);
    if (!options.wakeEnabled()) {
      reset();
      return Optional.of(text);
    }
    String body = text.strip();
    boolean armed = target.equals(armedTarget) && clock.instant().isBefore(armedUntil);
    reset();
    String word = options.wakeWord().strip();
    String alternate = word.equals("れい") ? "レイ" : word;
    String matched = prefix(body, word) ? word : prefix(body, alternate) ? alternate : null;
    if (matched == null) return armed && !body.isEmpty() ? Optional.of(body) : Optional.empty();
    body = body.substring(matched.length());
    int offset = 0;
    while (offset < body.length()) {
      int cp = body.codePointAt(offset);
      if (!boundary(cp)) break;
      offset += Character.charCount(cp);
    }
    body = body.substring(offset).strip();
    if (body.isEmpty()) {
      armedTarget = target;
      armedUntil = clock.instant().plusSeconds(60);
      return Optional.empty();
    }
    return Optional.of(body);
  }

  /** 音声による対話において、接頭辞に該当するか判定する。 */
  private static boolean prefix(String body, String word) {
    return body.startsWith(word)
        && (body.length() == word.length() || boundary(body.codePointAt(word.length())));
  }

  /** 音声による対話において、境界に該当するか判定する。 */
  private static boolean boundary(int cp) {
    return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || "、。,.!?！？：:".indexOf(cp) >= 0;
  }
}
