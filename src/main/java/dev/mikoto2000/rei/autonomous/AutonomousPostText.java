package dev.mikoto2000.rei.autonomous;

import java.text.Normalizer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Text-only draft rules; no handle lookup, facets, link preview, or network access. */
public final class AutonomousPostText {
  private static final Pattern SPACING = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
  private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
  private static final Pattern EMAIL =
      Pattern.compile(
          "[A-Za-z0-9](?:[A-Za-z0-9._%+-]*[A-Za-z0-9_%+-])?@(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,63}");
  private static final Pattern MENTION =
      Pattern.compile("@[\\p{Cf}]*[A-Za-z0-9](?:[A-Za-z0-9.\\p{Cf}_-]*[A-Za-z0-9])?");

  private AutonomousPostText() {}

  /** Comparison only: never replace the reviewed body or its publication hash with this key. */
  public static String duplicateKey(String text) {
    return SPACING
        .matcher(Normalizer.normalize(text, Normalizer.Form.NFKC))
        .replaceAll(" ")
        .strip();
  }

  static void validate(String text) {
    requireReadable(text);
    String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
    var literalAddresses =
        Stream.concat(URL.matcher(normalized).results(), EMAIL.matcher(normalized).results())
            .toList();
    var mentions = MENTION.matcher(normalized);
    while (mentions.find()) {
      if (literalAddresses.stream()
          .noneMatch(
              address -> mentions.start() >= address.start() && mentions.end() <= address.end()))
        throw new IllegalArgumentException("Draft mentions are not supported");
    }
  }

  static void requireReadable(String text) {
    if (Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("\\p{Cf}", "").isBlank())
      throw new IllegalArgumentException("Empty review text");
    for (int index = 0; index < text.length(); index++) {
      char value = text.charAt(index);
      boolean crlf = value == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n';
      boolean control = Character.isISOControl(value) && value != '\n' && value != '\t' && !crlf;
      boolean bidi =
          value >= '\u202A' && value <= '\u202E' || value >= '\u2066' && value <= '\u2069';
      if (control || bidi)
        throw new IllegalArgumentException("Draft text contains review control characters");
    }
  }
}
