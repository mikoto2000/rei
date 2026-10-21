package dev.mikoto2000.rei.skills;

import java.util.List;

/** スキル候補の結果を基準に照らして評価する。 */
public final class SkillCandidateEvaluator {

  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private SkillCandidateEvaluator() {}

  /** 対象を判断基準に照らして評価する。 */
  public static SkillCandidateEvaluation evaluate(
      List<SkillCandidate> candidates, String actualSelectedSkill) {
    if (actualSelectedSkill == null || actualSelectedSkill.isBlank()) {
      return new SkillCandidateEvaluation(false, null, null, null);
    }
    List<SkillCandidate> safeCandidates = candidates == null ? List.of() : candidates;
    return new SkillCandidateEvaluation(
        true,
        contains(safeCandidates, actualSelectedSkill, 1),
        contains(safeCandidates, actualSelectedSkill, 3),
        contains(safeCandidates, actualSelectedSkill, 5));
  }

  /** スキルの利用で判断に用いる含有の条件を確認する。 */
  private static boolean contains(List<SkillCandidate> candidates, String skillName, int limit) {
    return candidates.stream()
        .limit(limit)
        .anyMatch(candidate -> candidate.skill().name().equals(skillName));
  }
}
