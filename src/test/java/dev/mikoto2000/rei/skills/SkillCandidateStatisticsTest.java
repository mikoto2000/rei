package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SkillCandidateStatisticsTest {

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
