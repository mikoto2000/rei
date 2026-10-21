package dev.mikoto2000.rei.externalagent;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 外部エージェントへの委譲で受け渡す受け入れ条件評価を表す。
 *
 * <p>Criterion evidence is bound to one receipt/specification/patch; model success is never proof.
 *
 * @param criterionId 判断基準識別子
 * @param status 状態
 * @param evidence 根拠
 * @param explanation 説明
 * @param evaluator 評価の判断
 * @param evaluatedPatchSha256 評価済み変更差分内容の同一性を照合するSHA-256ハッシュ
 */
public record AcceptanceEvaluation(
    String criterionId,
    String status,
    List<String> evidence,
    String explanation,
    String evaluator,
    String evaluatedPatchSha256) {
  /** 受け取った情報からAcceptanceEvaluationを作成する。 */
  public AcceptanceEvaluation {
    if (!Set.of("VERIFIED", "FAILED", "NOT_VERIFIED").contains(status))
      throw new IllegalArgumentException("Invalid acceptance status");
    evidence = List.copyOf(evidence);
  }

  /** 外部エージェントへの委譲で用いる親を返す。 */
  static List<AcceptanceEvaluation> parent(
      ImplementationSpecification specification,
      IsolatedImplementationService.Receipt receipt,
      List<AcceptanceEvaluation> input) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (input == null || input.size() > 16 || receipt.patchHash() == null)
      throw new IllegalArgumentException("Bounded patch-bound evaluations required");
    var byId = new HashMap<String, AcceptanceEvaluation>();
    for (var evaluation : input) {
      if (evaluation == null
          || !"PARENT_LLM".equals(evaluation.evaluator())
          || !Objects.equals(receipt.patchHash(), evaluation.evaluatedPatchSha256())
          || evaluation.explanation() == null
          || evaluation.explanation().isBlank()
          || evaluation.explanation().length() > 1000
          || evaluation.evidence().size() > 16
          || evaluation.evidence().stream().anyMatch(e -> e == null || e.length() > 512)
          || byId.putIfAbsent(evaluation.criterionId(), evaluation) != null
          || specification.acceptanceCriteria().stream()
              .noneMatch(c -> c.id().equals(evaluation.criterionId())))
        throw new IllegalArgumentException(
            "Evaluation must identify a unique saved criterion, exact patch and PARENT_LLM evaluator");
    }
    return unverified(specification, receipt).stream()
        .map(
            original -> {
              var proposed = byId.get(original.criterionId());
              if (proposed == null) return original;
              if (!proposed.evidence().contains("receipt:" + receipt.id())
                  || !proposed.evidence().contains("patch:" + receipt.patchHash()))
                return new AcceptanceEvaluation(
                    original.criterionId(),
                    "NOT_VERIFIED",
                    List.of(),
                    "Parent assertion lacks receipt/patch evidence; Codex success is insufficient",
                    "PARENT_LLM",
                    receipt.patchHash());
              return proposed;
            })
        .toList();
  }

  /** 外部エージェントへの委譲で用いる未確認を返す。 */
  static List<AcceptanceEvaluation> unverified(
      ImplementationSpecification specification, IsolatedImplementationService.Receipt receipt) {
    return specification.acceptanceCriteria().stream()
        .map(
            c ->
                new AcceptanceEvaluation(
                    c.id(),
                    "NOT_VERIFIED",
                    receipt.patchHash() == null
                        ? List.of()
                        : List.of("receipt:" + receipt.id(), "patch:" + receipt.patchHash()),
                    "Administrator test exit and static review do not establish this business requirement; parent semantic evaluation required",
                    "SERVER_EVIDENCE_LIMIT",
                    receipt.patchHash()))
        .toList();
  }
}
