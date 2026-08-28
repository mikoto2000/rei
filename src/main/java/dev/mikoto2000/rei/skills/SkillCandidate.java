package dev.mikoto2000.rei.skills;

import java.util.List;

public record SkillCandidate(
    AgentSkill skill,
    int score,
    List<String> matchedFields,
    List<String> matchedKeywords) {

  public SkillCandidate {
    matchedFields = matchedFields == null ? List.of() : List.copyOf(matchedFields);
    matchedKeywords = matchedKeywords == null ? List.of() : List.copyOf(matchedKeywords);
  }
}
