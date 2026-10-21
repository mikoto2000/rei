package dev.mikoto2000.rei.event;

import java.util.List;

/**
 * Lexical Skill candidate selector の shadow mode 評価結果。
 *
 * @param totalSkillCount 合計スキル件数
 * @param candidateCount 候補件数
 * @param durationMs 継続時間ミリ秒
 * @param actualSelectedSkill 実際の選択中スキル
 * @param selected 選択中
 * @param top1Hit 上位1検索結果
 * @param top3Hit 上位3検索結果
 * @param top5Hit 上位5検索結果
 * @param topCandidates 上位候補一覧
 */
public record SkillCandidatesEvaluatedPayload(
    int totalSkillCount,
    int candidateCount,
    long durationMs,
    String actualSelectedSkill,
    boolean selected,
    Boolean top1Hit,
    Boolean top3Hit,
    Boolean top5Hit,
    List<CandidateScore> topCandidates)
    implements AgentEventPayload {

  /** 受け取った情報からSkillCandidatesEvaluatedPayloadを作成する。 */
  public SkillCandidatesEvaluatedPayload {
    topCandidates =
        topCandidates == null ? List.of() : List.copyOf(topCandidates.stream().limit(5).toList());
  }

  /**
   * 実行状況の通知で受け渡す候補評価値を表す。
   *
   * @param skill スキル
   * @param score 評価値
   */
  public record CandidateScore(String skill, int score) {}
}
