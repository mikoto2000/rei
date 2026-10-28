package dev.mikoto2000.rei.subagent;

import java.util.List;
import java.util.Set;

/**
 * 補助エージェントツールの判断基準をまとめる。
 *
 * <p>YAML requests capabilities; only this audited allowlist grants them. No MCP or delegation.
 */
public final class SubAgentToolPolicy {
  /** 許可。 */
  private static final Set<String> ALLOWED =
      Set.of(
          "readMultiFile",
          "grepMultiQuery",
          "searchAndRead",
          "searchFilePage",
          "readPdfFile",
          "readRawToolResult",
          "beginReview",
          "readReviewFile",
          "submitReviewResult",
          "reviewStatus",
          "reviewPending",
          "restartReview",
          "webSearch",
          "webSearchAndRead",
          "httpRequestRead",
          "searchKnowledge",
          "repositoryMap",
          "changeTestImpact",
          "diagnoseTestReport",
          "diagnoseTestReports");

  /** 既知。 */
  private final Set<String> known;

  /** 指定された値や利用先を使って、SubAgentToolPolicyを初期化する。 */
  public SubAgentToolPolicy(Set<String> known) {
    this.known = Set.copyOf(known);
  }

  /** 補助エージェントへの委譲で用いる実際に使うツール一覧を返す。 */
  public List<String> effectiveTools(List<String> requested) {
    return requested.stream().filter(known::contains).filter(ALLOWED::contains).distinct().toList();
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  public void validate(List<String> requested) {
    for (String tool : requested) {
      if (!known.contains(tool)) throw new IllegalArgumentException("tools: unknown tool " + tool);
      if (!ALLOWED.contains(tool))
        throw new IllegalArgumentException("tools: " + tool + " not permitted for subagents");
    }
  }
}
