package dev.mikoto2000.rei.activity.behavior;

/**
 * 作業習慣の支援で受け渡す作業習慣通知を表す。
 *
 * <p>Only an authorized, structured assessment may reach the wording generator.
 *
 * @param episodeId 経験の記録識別子
 * @param assessment 評価
 */
public record BehaviorNotification(String episodeId, BehaviorAssessment assessment) {}
