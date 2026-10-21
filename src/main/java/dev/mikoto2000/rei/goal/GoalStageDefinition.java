package dev.mikoto2000.rei.goal;

import java.util.List;

/**
 * 目標の達成管理で受け渡す目標段階定義を表す。
 *
 * <p>Immutable verification contract; Run and planner step identities are intentionally separate.
 *
 * @param id 識別子
 * @param parentStageId 親段階識別子
 * @param objective 目的
 * @param criteria 判断基準一覧
 * @param dependsOnStageIds depends通知時段階識別子一覧
 * @param requirementIds 要件識別子一覧
 * @param completion 完了条件
 */
public record GoalStageDefinition(
    String id,
    String parentStageId,
    String objective,
    List<GoalRepository.FileCriterion> criteria,
    List<String> dependsOnStageIds,
    List<String> requirementIds,
    GoalCompletionGate.Definition completion) {
  /** 受け取った情報からGoalStageDefinitionを作成する。 */
  public GoalStageDefinition {
    criteria = criteria == null ? List.of() : List.copyOf(criteria);
    dependsOnStageIds = dependsOnStageIds == null ? List.of() : List.copyOf(dependsOnStageIds);
    requirementIds = requirementIds == null ? List.of() : List.copyOf(requirementIds);
  }
}
