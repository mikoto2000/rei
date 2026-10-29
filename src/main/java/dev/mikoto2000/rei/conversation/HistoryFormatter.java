package dev.mikoto2000.rei.conversation;

import dev.mikoto2000.rei.event.CredentialRedactor;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 履歴を利用者が確認できる表示形式へ整える。
 *
 * <p>Plain terminal view; no command parsing or persistence responsibility.
 */
public final class HistoryFormatter {
  /** 秘密情報と制御文字を除去し、文字数で省略せず本文を返す。 */
  public String body(String text) {
    return safe(text);
  }

  /** 会話履歴と受付記録の管理で用いる表示名を返す。 */
  public String label(String text) {
    return clip(safe(text).replaceAll("\\s+", " ").strip(), 200);
  }

  /** 利用先の制約に合わせて、指定された値を扱える形に整える。 */
  private String safe(String text) {
    return CredentialRedactor.redact(text)
        .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "")
        .replaceAll("[\\u202A-\\u202E\\u2066-\\u2069]", "");
  }

  /** 利用先の許容量を超えないよう、履歴表示形式への変換を上限内に収める。 */
  private String clip(String value, int length) {
    if (value.codePointCount(0, value.length()) <= length) return value;
    return value.substring(0, value.offsetByCodePoints(0, length)) + "... (truncated)";
  }

  /** 会話履歴と受付記録の管理で用いる記録日時を返す。 */
  public String timestamp(String iso) {
    try {
      return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
          .withZone(ZoneId.systemDefault())
          .format(Instant.parse(iso));
    } catch (RuntimeException ignored) {
      return label(iso);
    }
  }

  /** 会話履歴と受付記録の管理で用いるlogical識別子を返す。 */
  public String logicalId(String id) {
    return id != null && id.startsWith("project:") ? id.substring(id.indexOf(':', 8) + 1) : id;
  }

  /** 会話履歴と受付記録の管理で用いるメッセージを返す。 */
  public String message(ConversationLogEntry entry) {
    return "["
        + timestamp(entry.timestamp().toInstant().toString())
        + "] "
        + label(entry.speaker())
        + "\n"
        + body(entry.content())
        + "\n";
  }
}
