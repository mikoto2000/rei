package dev.mikoto2000.rei.event;

import java.util.List;

/** Lexical Skill candidate selector の shadow mode 評価結果。 */
public record SkillCandidatesEvaluatedPayload(
    int totalSkillCount,
    int candidateCount,
    long durationMs,
    String actualSelectedSkill,
    boolean selected,
    Boolean top1Hit,
    Boolean top3Hit,
    Boolean top5Hit,
    List<CandidateScore> topCandidates) implements AgentEventPayload {

  public SkillCandidatesEvaluatedPayload {
    topCandidates = topCandidates == null ? List.of() : List.copyOf(topCandidates.stream().limit(5).toList());
  }

  public record CandidateScore(String skill, int score) {
  }
}
