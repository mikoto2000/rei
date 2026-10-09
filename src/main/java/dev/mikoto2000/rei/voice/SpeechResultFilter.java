package dev.mikoto2000.rei.voice;
import java.util.Optional;
public final class SpeechResultFilter {
  private SpeechResultFilter() {}
  public static Optional<String> filter(String text) {
    if (text == null || text.length() > 16384) return Optional.empty();
    String cleaned = text.strip();
    if (cleaned.isEmpty() || cleaned.startsWith("/")
        || cleaned.codePoints().noneMatch(Character::isLetterOrDigit)
        || cleaned.codePoints().anyMatch(c -> Character.isISOControl(c) && !Character.isWhitespace(c))) {
      return Optional.empty();
    }
    return Optional.of(cleaned);
  }
}