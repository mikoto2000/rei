package dev.mikoto2000.rei.skills;

import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Component;

@Component
public class SkillCandidateStatistics {
  private final LongAdder evaluations = new LongAdder();
  private final LongAdder top1Hits = new LongAdder();
  private final LongAdder top3Hits = new LongAdder();
  private final LongAdder top5Hits = new LongAdder();

  public void record(SkillCandidateEvaluation evaluation) {
    if (evaluation == null || !evaluation.selected()) return;
    evaluations.increment();
    if (Boolean.TRUE.equals(evaluation.top1Hit())) top1Hits.increment();
    if (Boolean.TRUE.equals(evaluation.top3Hit())) top3Hits.increment();
    if (Boolean.TRUE.equals(evaluation.top5Hit())) top5Hits.increment();
  }

  public Snapshot snapshot() {
    return new Snapshot(evaluations.sum(), top1Hits.sum(), top3Hits.sum(), top5Hits.sum());
  }

  public record Snapshot(long evaluations, long top1Hits, long top3Hits, long top5Hits) {
  }
}
