package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知に必要な失敗情報メッセージ一覧を扱う。
 *
 * <p>Bounded, redacted diagnostics shared by commands and terminal clients.
 */
public final class FailureMessages {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private FailureMessages() {}

  /** 実行状況の通知で用いる説明を返す。 */
  public static String describe(Throwable error) {
    if (error == null) return "原因の詳細がありません。";
    var seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    var reasons = new java.util.LinkedHashSet<String>();
    for (var current = error;
        current != null && seen.size() < 8 && seen.add(current);
        current = current.getCause()) {
      String message = current.getMessage();
      reasons.add(
          text(
              message == null || message.isBlank() ? current.getClass().getSimpleName() : message));
    }
    return text(String.join(" → ", reasons));
  }

  /** 表示や受け渡しに用いるテキストを返す。 */
  public static String text(String reason) {
    if (reason == null || reason.isBlank()) return "原因の詳細がありません。";
    String safe =
        CredentialRedactor.redact(reason)
            .replaceAll("[\\p{Cntrl}]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    return safe.length() <= 600 ? safe : safe.substring(0, 599) + "…";
  }
}
