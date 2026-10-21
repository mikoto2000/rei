package dev.mikoto2000.rei.skills;

/**
 * スキルの利用で受け渡すスキル候補評価を表す。
 *
 * @param selected 選択中
 * @param top1Hit 上位1検索結果
 * @param top3Hit 上位3検索結果
 * @param top5Hit 上位5検索結果
 */
public record SkillCandidateEvaluation(
    boolean selected, Boolean top1Hit, Boolean top3Hit, Boolean top5Hit) {}
