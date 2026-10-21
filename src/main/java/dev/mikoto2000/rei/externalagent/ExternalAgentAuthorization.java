package dev.mikoto2000.rei.externalagent;

import java.util.Locale;

/**
 * 外部エージェントへの委譲に必要な外部エージェント権限の確認を扱う。
 *
 * <p>Conservative gate over actual user input, never tool arguments or repository text.
 */
public final class ExternalAgentAuthorization {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ExternalAgentAuthorization() {}

  /** 外部エージェントへの委譲において、明示的な要求に該当するか判定する。 */
  public static boolean explicitRequest(String input, ExternalAgentRequest.Agent agent) {
    if (input == null) return false;
    if (input.strip().startsWith("/agent ")) {
      try {
        var command = ExternalAgentCommandRequest.parse(input);
        return reviewAction(command)
            && command.agent().equals(agent.name().toLowerCase(Locale.ROOT));
      } catch (IllegalArgumentException invalid) {
        return false;
      }
    }
    if (agent == ExternalAgentRequest.Agent.CODEX) return explicitRequest(input);
    return explicitRequest(
        input
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\bcodex\\b", "other-provider")
            .replaceAll("\\bclaude(?:\\s+code)?\\b", "codex"));
  }

  /** 外部エージェントへの委譲において、明示的な並列要求に該当するか判定する。 */
  public static boolean explicitParallelRequest(String input) {
    return explicitParallelRequest(input, ExternalAgentRequest.Agent.CODEX);
  }

  /** 外部エージェントへの委譲において、明示的な並列要求に該当するか判定する。 */
  public static boolean explicitParallelRequest(String input, ExternalAgentRequest.Agent agent) {
    if (!explicitRequest(input, agent) || input.strip().startsWith("/agent ")) return false;
    String text = input.toLowerCase(Locale.ROOT).replaceAll("(?s)```.*?```|「[^」]*」|\"[^\"]*\"", "");
    if (text.matches("(?s).*(並列.{0,8}(しない|せず|不要|禁止)|not.{0,12}parallel|no parallel|sequential).*"))
      return false;
    return text.contains("並列") || text.matches("(?s).*\\bparallel\\b.*");
  }

  /** 外部エージェントへの委譲において、明示的な修正提案要求に該当するか判定する。 */
  public static boolean explicitFixProposalRequest(String input) {
    return explicitFixProposalRequest(input, ExternalAgentRequest.Agent.CODEX);
  }

  /** 外部エージェントへの委譲において、明示的な修正提案要求に該当するか判定する。 */
  public static boolean explicitFixProposalRequest(String input, ExternalAgentRequest.Agent agent) {
    if (input == null) return false;
    String text = input.toLowerCase(Locale.ROOT);
    String normalized =
        text.replace("修正案", "レビュー")
            .replace("修正を提案", "レビューして")
            .replace("fix proposal", "review")
            .replace("propose a fix", "review")
            .replace("propose fixes", "review");
    return !normalized.equals(text) && explicitRequest(normalized, agent);
  }

  /** 外部エージェントへの委譲において、明示的な継続入力要求に該当するか判定する。 */
  public static boolean explicitContinuationRequest(
      String input, ExternalAgentRequest.Agent agent) {
    if (!explicitRequest(input, agent)) return false;
    String text = input.toLowerCase(Locale.ROOT).replaceAll("(?s)```.*?```|「[^」]*」|\"[^\"]*\"", "");
    return text.contains("続")
        || text.contains("再開")
        || text.matches("(?s).*\\b(?:continue|continuation|resume)\\b.*");
  }

  /** 外部エージェントへの委譲において、明示的な要求に該当するか判定する。 */
  public static boolean explicitRequest(String input) {
    if (input == null) return false;
    if (input.strip().startsWith("/agent ")) {
      try {
        var command = ExternalAgentCommandRequest.parse(input);
        return reviewAction(command) && command.agent().equals("codex");
      } catch (IllegalArgumentException error) {
        return false;
      }
    }
    String text = input.toLowerCase(Locale.ROOT);
    if (text.matches("(?s).*(翻訳|英訳|という|explain|translate|how to|example).*")) return false;
    text = text.replaceAll("(?s)```.*?```|「[^」]*」|\"[^\"]*\"", "");
    if (text.matches("(?s).*(do not|don't|without|never|使わ|利用しない|使用しない|させない|頼まない|依頼しない|出さない|不要).*"))
      return false;
    // A follow-up can explicitly delegate to Codex without repeating "review".
    // This authorizes read-only review only. Fix proposals have an additional explicit gate.
    String delegation = "(?:依頼(?:を)?(?:出して|して|お願いします)|頼んで|お願い(?:して|します))";
    return text.contains("codex")
        && (text.matches(
                "(?s).*codex\\s*(?:[にのでへ]|を(?:使って|利用して|使用して)).*(レビュー.*(して|させて|もら|依頼|頼|お願い)|意見.*(聞|きい)).*")
            || text.matches("(?s).*codex\\s*[にへ].*" + delegation + ".*")
            || text.matches(
                "(?s).*(ask|use|have|let|request|please).*\\bcodex\\b.*\\breview\\b.*"));
  }

  /** 外部エージェントへの委譲において、レビュー操作に該当するか判定する。 */
  private static boolean reviewAction(ExternalAgentCommandRequest command) {
    return command.action().equals("review") || command.action().equals("material-review");
  }
}
