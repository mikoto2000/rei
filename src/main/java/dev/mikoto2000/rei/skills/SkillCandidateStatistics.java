package dev.mikoto2000.rei.skills;

import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/** スキルの利用に必要なスキル候補統計を扱う。 */
@Component
public class SkillCandidateStatistics {
  /** 評価一覧。 */
  private final LongAdder evaluations = new LongAdder();

  /** 上位1検索結果。 */
  private final LongAdder top1Hits = new LongAdder();

  /** 上位3検索結果。 */
  private final LongAdder top3Hits = new LongAdder();

  /** 上位5検索結果。 */
  private final LongAdder top5Hits = new LongAdder();

  /** 後から経緯を確認できるよう、指定された内容を記録する。 */
  public void record(SkillCandidateEvaluation evaluation) {
    if (evaluation == null || !evaluation.selected()) return;
    evaluations.increment();
    if (Boolean.TRUE.equals(evaluation.top1Hit())) top1Hits.increment();
    if (Boolean.TRUE.equals(evaluation.top3Hit())) top3Hits.increment();
    if (Boolean.TRUE.equals(evaluation.top5Hit())) top5Hits.increment();
  }

  /** 現在の状態を、後から参照できる記録として取り出す。 */
  public Snapshot snapshot() {
    return new Snapshot(evaluations.sum(), top1Hits.sum(), top3Hits.sum(), top5Hits.sum());
  }

  /**
   * スキルの利用で受け渡す状態の記録を表す。
   *
   * @param evaluations 評価一覧
   * @param top1Hits 上位1検索結果
   * @param top3Hits 上位3検索結果
   * @param top5Hits 上位5検索結果
   */
  public record Snapshot(long evaluations, long top1Hits, long top3Hits, long top5Hits) {}
}
