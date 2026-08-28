package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class SkillCandidateEvaluatorTest {

  @Test
  void doesNotTreatNoSelectionAsMiss() {
    SkillCandidateEvaluation evaluation = SkillCandidateEvaluator.evaluate(List.of(candidate("a")), null);

    assertThat(evaluation.selected()).isFalse();
    assertThat(evaluation.top1Hit()).isNull();
    assertThat(evaluation.top3Hit()).isNull();
    assertThat(evaluation.top5Hit()).isNull();
  }

  @Test
  void reportsTop1Hit() {
    SkillCandidateEvaluation evaluation = SkillCandidateEvaluator.evaluate(candidates("a", "b"), "a");

    assertThat(evaluation.top1Hit()).isTrue();
    assertThat(evaluation.top3Hit()).isTrue();
    assertThat(evaluation.top5Hit()).isTrue();
  }

  @Test
  void reportsTop3HitOutsideTop1() {
    SkillCandidateEvaluation evaluation = SkillCandidateEvaluator.evaluate(candidates("a", "b", "c"), "c");

    assertThat(evaluation.top1Hit()).isFalse();
    assertThat(evaluation.top3Hit()).isTrue();
    assertThat(evaluation.top5Hit()).isTrue();
  }

  @Test
  void reportsTop5Miss() {
    SkillCandidateEvaluation evaluation = SkillCandidateEvaluator.evaluate(candidates("a", "b", "c", "d", "e"), "z");

    assertThat(evaluation.top1Hit()).isFalse();
    assertThat(evaluation.top3Hit()).isFalse();
    assertThat(evaluation.top5Hit()).isFalse();
  }

  private List<SkillCandidate> candidates(String... names) {
    return java.util.Arrays.stream(names).map(this::candidate).toList();
  }

  private SkillCandidate candidate(String name) {
    AgentSkill skill = new AgentSkill(name, "", true, Path.of(name), Path.of(name, "SKILL.md"), "");
    return new SkillCandidate(skill, 1, List.of(), List.of());
  }
}
