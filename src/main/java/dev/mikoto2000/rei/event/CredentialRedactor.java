package dev.mikoto2000.rei.event;

import java.util.regex.Pattern;

/** Shared redaction for event summaries and history display; always apply before clipping. */
public final class CredentialRedactor {
  private CredentialRedactor() {}
  private static final String KEYS = "api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|secret|credential|authorization|accessJwt|refreshJwt";
  private static final Pattern QUOTED_SECRET = Pattern.compile(
      "(?i)([\"'](?:" + KEYS + ")[\"']\\s*:\\s*)(\"(?:\\\\.|[^\"\\\\])*\"|'[^']*')");
  private static final Pattern ASSIGNMENT = Pattern.compile(
      "(?i)((?:" + KEYS + ")\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|(?:Bearer|Basic)\\s+\\S+|\\S+)");
  public static String redact(String value) {
    if (value == null) return "";
    String safe = QUOTED_SECRET.matcher(value).replaceAll("$1\"[REDACTED]\"");
    safe = ASSIGNMENT.matcher(safe).replaceAll("$1[REDACTED]");
    return safe.replaceAll("(?s)-----BEGIN (?:[A-Z ]+)?PRIVATE KEY-----.*?-----END (?:[A-Z ]+)?PRIVATE KEY-----", "[REDACTED]")
        .replaceAll("\\bsk-[A-Za-z0-9_-]{16,}\\b", "[REDACTED]");
  }
}
