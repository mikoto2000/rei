package dev.mikoto2000.rei.topic;

/**
 * 話題の管理で受け渡す話題評価値Breakdownを表す。
 *
 * @param priorityContribution 優先度貢献度
 * @param freshnessContribution 情報の鮮度貢献度
 * @param usefulnessContribution 有用性貢献度
 * @param confidenceContribution 確信度貢献度
 * @param intrusivenessPenalty 割り込みの負担減点
 * @param repetitionPenalty 同じ作業の繰り返し減点
 * @param finalScore 最終評価値
 */
public record TopicScoreBreakdown(
    double priorityContribution,
    double freshnessContribution,
    double usefulnessContribution,
    double confidenceContribution,
    double intrusivenessPenalty,
    double repetitionPenalty,
    double finalScore) {}
