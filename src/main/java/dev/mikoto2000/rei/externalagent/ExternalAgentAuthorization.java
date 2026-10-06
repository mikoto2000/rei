package dev.mikoto2000.rei.externalagent;

import java.util.Locale;

/** Conservative gate over actual user input, never tool arguments or repository text. */
public final class ExternalAgentAuthorization {
  private ExternalAgentAuthorization() {}
  public static boolean explicitParallelRequest(String input) {
    if(!explicitRequest(input)||input.strip().startsWith("/agent "))return false;
    String text=input.toLowerCase(Locale.ROOT).replaceAll("(?s)```.*?```|「[^」]*」|\"[^\"]*\"","");
    if(text.matches("(?s).*(並列.{0,8}(しない|せず|不要|禁止)|not.{0,12}parallel|no parallel|sequential).*"))return false;
    return text.contains("並列")||text.matches("(?s).*\\bparallel\\b.*");
  }
  public static boolean explicitFixProposalRequest(String input) {
    if(input==null)return false;
    String text=input.toLowerCase(Locale.ROOT);
    String normalized=text.replace("修正案","レビュー").replace("修正を提案","レビューして")
        .replace("fix proposal","review").replace("propose a fix","review").replace("propose fixes","review");
    return !normalized.equals(text) && explicitRequest(normalized);
  }
  public static boolean explicitRequest(String input) {
    if (input == null) return false;
    if (input.strip().startsWith("/agent ")) {
      try { ExternalAgentCommandRequest.parse(input); return true; }
      catch (IllegalArgumentException error) { return false; }
    }
    String text = input.toLowerCase(Locale.ROOT);
    if (text.matches("(?s).*(翻訳|英訳|という|explain|translate|how to|example).*")) return false;
    text = text.replaceAll("(?s)```.*?```|「[^」]*」|\"[^\"]*\"", "");
    if (text.matches("(?s).*(do not|don't|without|never|使わ|利用しない|使用しない|させない|頼まない|依頼しない|出さない|不要).*")) return false;
    // A follow-up can explicitly delegate to Codex without repeating "review".
    // This authorizes read-only review only. Fix proposals have an additional explicit gate.
    String delegation = "(?:依頼(?:を)?(?:出して|して|お願いします)|頼んで|お願い(?:して|します))";
    return text.contains("codex") && (text.matches("(?s).*codex\\s*(?:[にのでへ]|を(?:使って|利用して|使用して)).*(レビュー.*(して|させて|もら|依頼|頼|お願い)|意見.*(聞|きい)).*")
        || text.matches("(?s).*codex\\s*[にへ].*" + delegation + ".*")
        || text.matches("(?s).*(ask|use|have|let|request|please).*\\bcodex\\b.*\\breview\\b.*"));
  }
}
