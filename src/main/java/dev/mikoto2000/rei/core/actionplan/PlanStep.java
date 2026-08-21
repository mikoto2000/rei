package dev.mikoto2000.rei.core.actionplan;

/**
 * Action Plan の 1 ステップ。
 *
 * @param id          安定したステップ ID
 * @param description ステップの説明
 * @param status      状態
 * @param order       順序
 * @param failureCount 失敗回数
 */
public record PlanStep(
    String id,
    String description,
    String status,
    int order,
    int failureCount) {
}
