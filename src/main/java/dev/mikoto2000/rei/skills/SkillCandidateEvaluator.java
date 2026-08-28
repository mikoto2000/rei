package dev.mikoto2000.rei.skills;

import java.util.List;

public final class SkillCandidateEvaluator {

  private SkillCandidateEvaluator() {
  }

  public static SkillCandidateEvaluation evaluate(List<SkillCandidate> candidates, String actualSelectedSkill) {
    if (actualSelectedSkill == null || actualSelectedSkill.isBlank()) {
      return new SkillCandidateEvaluation(false, null, null, null);
    }
    List<SkillCandidate> safeCandidates = candidates == null ? List.of() : candidates;
    return new SkillCandidateEvaluation(true,
        contains(safeCandidates, actualSelectedSkill, 1),
        contains(safeCandidates, actualSelectedSkill, 3),
        contains(safeCandidates, actualSelectedSkill, 5));
  }

  private static boolean contains(List<SkillCandidate> candidates, String skillName, int limit) {
    return candidates.stream().limit(limit).anyMatch(candidate -> candidate.skill().name().equals(skillName));
  }
}
