package dev.mikoto2000.rei.toolcontract;

import java.util.regex.Pattern;

/**
 * 実行状況の通知に必要な認証情報Redactorを扱う。
 *
 * <p>Shared redaction for event summaries and history display; always apply before clipping.
 */
public final class CredentialMask {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private CredentialMask() {}

  /** キー一覧。 */
  private static final String KEYS =
      "api[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token(?:[_-]?hint)?|code[_-]?verifier|token|password|secret|credential|authorization|accessJwt|refreshJwt";

  // Possessive chunks avoid recursive backtracking on large quoted JSON credentials.
  /** 引用済み秘密情報。 */
  private static final Pattern QUOTED_SECRET =
      Pattern.compile(
          "(?i)([\"'](?:" + KEYS + ")[\"']\\s*:\\s*)(\"(?:[^\"\\\\]++|\\\\.)*+\"|'[^']*')");

  /** 代入の記述を見つける照合規則。 */
  private static final Pattern ASSIGNMENT =
      Pattern.compile(
          "(?i)((?:" + KEYS + ")\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|(?:Bearer|Basic)\\s+\\S+|\\S+)");

  /** 実行状況の通知で用いるredactを返す。 */
  public static String redact(String value) {
    if (value == null) return "";
    String safe = QUOTED_SECRET.matcher(value).replaceAll("$1\"[REDACTED]\"");
    safe = ASSIGNMENT.matcher(safe).replaceAll("$1[REDACTED]");
    return safe.replaceAll(
            "(?s)-----BEGIN (?:[A-Z ]+)?PRIVATE KEY-----.*?-----END (?:[A-Z ]+)?PRIVATE KEY-----",
            "[REDACTED]")
        .replaceAll("\\bsk-[A-Za-z0-9_-]{16,}\\b", "[REDACTED]")
        .replaceAll("\\bx(?:ox[baprs]|app)-[A-Za-z0-9-]+", "[REDACTED]");
  }
}
