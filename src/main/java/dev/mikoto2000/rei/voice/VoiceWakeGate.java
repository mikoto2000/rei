package dev.mikoto2000.rei.voice;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import dev.mikoto2000.rei.application.input.ConversationTarget;

/** Post-ASR wake prefix gate. It still requires ASR and is not speaker identification. */
public final class VoiceWakeGate {
  private final Clock clock;
  private ConversationTarget armedTarget;
  private Instant armedUntil;
  public VoiceWakeGate(Clock clock) { this.clock = Objects.requireNonNull(clock); }
  public synchronized void reset() { armedTarget = null; armedUntil = null; }
  public synchronized Optional<String> filter(String text, ConversationTarget target, VoiceAdvancedOptions options) {
    Objects.requireNonNull(text); Objects.requireNonNull(target); Objects.requireNonNull(options);
    if (!options.wakeEnabled()) { reset(); return Optional.of(text); }
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
      armedTarget = target; armedUntil = clock.instant().plusSeconds(60);
      return Optional.empty();
    }
    return Optional.of(body);
  }
  private static boolean prefix(String body, String word) {
    return body.startsWith(word) && (body.length() == word.length() || boundary(body.codePointAt(word.length())));
  }
  private static boolean boundary(int cp) {
    return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || "、。,.!?！？：:".indexOf(cp) >= 0;
  }
}
