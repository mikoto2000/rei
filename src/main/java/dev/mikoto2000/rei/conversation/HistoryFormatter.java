package dev.mikoto2000.rei.conversation;

import java.time.*;
import java.time.format.DateTimeFormatter;
import dev.mikoto2000.rei.event.CredentialRedactor;

/** Plain terminal view; no command parsing or persistence responsibility. */
public final class HistoryFormatter {
  public static final int MAX_BODY_CHARS = 2000;
  public String body(String text) { return clip(safe(text), MAX_BODY_CHARS); }
  public String label(String text) { return clip(safe(text).replaceAll("\\s+", " ").strip(), 200); }
  private String safe(String text) {
    return CredentialRedactor.redact(text).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "")
        .replaceAll("[\\u202A-\\u202E\\u2066-\\u2069]", "");
  }
  private String clip(String value, int length) {
    if (value.codePointCount(0, value.length()) <= length) return value;
    return value.substring(0, value.offsetByCodePoints(0, length)) + "... (truncated)";
  }
  public String timestamp(String iso) {
    try { return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.parse(iso)); }
    catch (RuntimeException ignored) { return label(iso); }
  }
  public String logicalId(String id) {
    return id != null && id.startsWith("project:") ? id.substring(id.indexOf(':', 8) + 1) : id;
  }
  public String message(ConversationLogEntry entry) {
    return "[" + timestamp(entry.timestamp().toInstant().toString()) + "] " + label(entry.speaker()) + "\n" + body(entry.content()) + "\n";
  }
}
