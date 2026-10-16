package dev.mikoto2000.rei.dot;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class DotAuthorization {
  static Set<String> categories(String input) {
    if (input == null) return Set.of();
    String clean = input.replaceAll("(?s)```.*?```|「[^」]*」|\\\"[^\\\"]*\\\"", "");
    String normalized =
        clean
            .replaceAll("(?i)\\b(?:tenten|dot)\\b|てんてん", "codex")
            .replace("技術調査", "レビュー")
            .replace("調査", "レビュー")
            .replace("比較", "レビュー")
            .replaceAll("(?i)research|comparison|compare", "review");
    if (!dev.mikoto2000.rei.externalagent.ExternalAgentAuthorization.explicitRequest(normalized))
      return Set.of();
    var allowed = new HashSet<String>();
    String lower = clean.toLowerCase(Locale.ROOT);
    if (lower.contains("調査") || lower.contains("research")) allowed.add("TECH_RESEARCH");
    if (lower.contains("比較") || lower.contains("compare") || lower.contains("comparison"))
      allowed.add("COMPARISON");
    if (lower.contains("レビュー") || lower.contains("review")) allowed.add("REVIEW");
    return Set.copyOf(allowed);
  }
}
