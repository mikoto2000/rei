package dev.mikoto2000.rei.skills;

public record SkillCandidateEvaluation(
    boolean selected,
    Boolean top1Hit,
    Boolean top3Hit,
    Boolean top5Hit) {
}
