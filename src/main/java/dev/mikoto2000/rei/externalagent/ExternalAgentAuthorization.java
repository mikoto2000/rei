package dev.mikoto2000.rei.externalagent;

import java.util.Locale;

/** Conservative gate over actual user input, never tool arguments or repository text. */
public final class ExternalAgentAuthorization {
  private ExternalAgentAuthorization() {}
  public static boolean explicitRequest(String input) {
    if (input == null) return false;
    if (input.strip().startsWith("/agent ")) {
      try { ExternalAgentCommandRequest.parse(input); return true; }
      catch (IllegalArgumentException error) { return false; }
    }
    String text = input.toLowerCase(Locale.ROOT);
    if (text.matches("(?s).*(翻訳|英訳|という|explain|translate|how to|example).*")) return false;
    text = text.replaceAll("(?s)```.*?```|「[^」]*」|\"[^\"]*\"", "");
    if (text.matches("(?s).*(do not|don't|without|never|使わ|利用しない|使用しない|させない|頼まない|不要).*")) return false;
    return text.contains("codex") && (text.matches("(?s).*codex\\s*[にの].*(レビュー.*(して|させて|もら|依頼|頼)|意見.*(聞|きい)).*")
        || text.matches("(?s).*(ask|use|have|let|request|please).*\\bcodex\\b.*\\breview\\b.*"));
  }
}
