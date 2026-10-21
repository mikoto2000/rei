package dev.mikoto2000.rei.slack;

import dev.mikoto2000.rei.event.CredentialRedactor;
import java.util.ArrayList;
import java.util.List;

/** Slackとの連携に必要なSlackRepliesを扱う。 */
public final class SlackReplies {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private SlackReplies() {}

  /** 利用先の制約に合わせて、指定された値を扱える形に整える。 */
  public static String safe(String text) {
    String result = CredentialRedactor.redact(text);
    for (String name : List.of("SLACK_BOT_TOKEN", "SLACK_APP_TOKEN")) {
      String secret = System.getenv(name);
      if (secret != null && !secret.isBlank()) result = result.replace(secret, "[REDACTED]");
    }
    return result;
  }

  /** Slackとの連携で用いる部分一覧を返す。 */
  public static List<String> parts(String text) {
    String safe =
        safe(text)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replaceAll("\\*\\*([^*]+)\\*\\*", "*$1*")
            .replaceAll("~~([^~]+)~~", "~$1~");
    var result = new ArrayList<String>();
    for (int start = 0; start < safe.length(); ) {
      int end = Math.min(start + 3000, safe.length());
      if (end < safe.length() && Character.isHighSurrogate(safe.charAt(end - 1))) end--;
      result.add(safe.substring(start, end));
      start = end;
    }
    return List.copyOf(result);
  }
}
