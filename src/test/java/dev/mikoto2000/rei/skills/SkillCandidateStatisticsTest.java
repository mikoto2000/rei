package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** スキル候補統計の結果と状態の契約を検証するテスト。 */
class SkillCandidateStatisticsTest {

  /** スキルの利用で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void aggregatesOnlyEvaluationsWithAnActualSelection() {
    SkillCandidateStatistics statistics = new SkillCandidateStatistics();
    statistics.record(new SkillCandidateEvaluation(false, null, null, null));
    statistics.record(new SkillCandidateEvaluation(true, true, true, true));
    statistics.record(new SkillCandidateEvaluation(true, false, true, true));
    statistics.record(new SkillCandidateEvaluation(true, false, false, false));

    SkillCandidateStatistics.Snapshot snapshot = statistics.snapshot();

    assertThat(snapshot.evaluations()).isEqualTo(3);
    assertThat(snapshot.top1Hits()).isEqualTo(1);
    assertThat(snapshot.top3Hits()).isEqualTo(2);
    assertThat(snapshot.top5Hits()).isEqualTo(2);
  }
}
