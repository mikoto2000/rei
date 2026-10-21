package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** スキル候補評価の判断の結果と状態の契約を検証するテスト。 */
class SkillCandidateEvaluatorTest {

  /** スキルの利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void doesNotTreatNoSelectionAsMiss() {
    SkillCandidateEvaluation evaluation =
        SkillCandidateEvaluator.evaluate(List.of(candidate("a")), null);

    assertThat(evaluation.selected()).isFalse();
    assertThat(evaluation.top1Hit()).isNull();
    assertThat(evaluation.top3Hit()).isNull();
    assertThat(evaluation.top5Hit()).isNull();
  }

  /** 上位1検索結果を伝えることを確認する。 */
  @Test
  void reportsTop1Hit() {
    SkillCandidateEvaluation evaluation =
        SkillCandidateEvaluator.evaluate(candidates("a", "b"), "a");

    assertThat(evaluation.top1Hit()).isTrue();
    assertThat(evaluation.top3Hit()).isTrue();
    assertThat(evaluation.top5Hit()).isTrue();
  }

  /** 上位3検索結果範囲外上位1を伝えることを確認する。 */
  @Test
  void reportsTop3HitOutsideTop1() {
    SkillCandidateEvaluation evaluation =
        SkillCandidateEvaluator.evaluate(candidates("a", "b", "c"), "c");

    assertThat(evaluation.top1Hit()).isFalse();
    assertThat(evaluation.top3Hit()).isTrue();
    assertThat(evaluation.top5Hit()).isTrue();
  }

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void reportsTop5Miss() {
    SkillCandidateEvaluation evaluation =
        SkillCandidateEvaluator.evaluate(candidates("a", "b", "c", "d", "e"), "z");

    assertThat(evaluation.top1Hit()).isFalse();
    assertThat(evaluation.top3Hit()).isFalse();
    assertThat(evaluation.top5Hit()).isFalse();
  }

  /** {@code candidates} の条件を再現するための補助情報を返す。 */
  private List<SkillCandidate> candidates(String... names) {
    return java.util.Arrays.stream(names).map(this::candidate).toList();
  }

  /** {@code candidate} の条件を再現するための補助情報を返す。 */
  private SkillCandidate candidate(String name) {
    AgentSkill skill = new AgentSkill(name, "", true, Path.of(name), Path.of(name, "SKILL.md"), "");
    return new SkillCandidate(skill, 1, List.of(), List.of());
  }
}
