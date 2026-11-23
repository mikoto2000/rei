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
      "api[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token(?:[_-]?hint)?|code[_-]?verifier|token|password|secret|credential|authorization|accessJwt|refreshJwt|[a-z0-9_-]+[_-](?:token|api[_-]?key)";

  /** Recognizable provider credentials, also shared by memory admission checks. */
  public static final String PROVIDER_TOKEN_REGEX =
      "\\b(?:AKIA[A-Z0-9]{16}|AIza[A-Za-z0-9_-]{35}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})";

  private static final Pattern PROVIDER_TOKEN = Pattern.compile(PROVIDER_TOKEN_REGEX);

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
    safe = PROVIDER_TOKEN.matcher(safe).replaceAll("[REDACTED]");
    return safe.replaceAll(
            "(?s)-----BEGIN (?:[A-Z ]+)?PRIVATE KEY-----.*?-----END (?:[A-Z ]+)?PRIVATE KEY-----",
            "[REDACTED]")
        .replaceAll("\\bsk-[A-Za-z0-9_-]{16,}\\b", "[REDACTED]")
        .replaceAll("\\bx(?:ox[baprs]|app)-[A-Za-z0-9-]+", "[REDACTED]");
  }
}
