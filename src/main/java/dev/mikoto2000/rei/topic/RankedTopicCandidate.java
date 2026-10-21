package dev.mikoto2000.rei.topic;

/**
 * 話題の管理で受け渡す順位を付けた話題候補を表す。
 *
 * @param candidate 候補
 * @param score 評価値
 * @param rejectionReason rejection理由
 */
public record RankedTopicCandidate(
    TopicCandidate candidate, TopicScoreBreakdown score, TopicRejectionReason rejectionReason) {
  /** 話題の管理で用いる最終評価値を返す。 */
  public double finalScore() {
    return score.finalScore();
  }
}
