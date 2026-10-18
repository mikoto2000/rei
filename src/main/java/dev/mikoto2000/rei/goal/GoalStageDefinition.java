package dev.mikoto2000.rei.goal;

import java.util.List;

/** Immutable verification contract; Run and planner step identities are intentionally separate. */
public record GoalStageDefinition(
    String id,
    String parentStageId,
    String objective,
    List<GoalRepository.FileCriterion> criteria,
    List<String> dependsOnStageIds,
    List<String> requirementIds,
    GoalCompletionGate.Definition completion) {
  public GoalStageDefinition {
    criteria = criteria == null ? List.of() : List.copyOf(criteria);
    dependsOnStageIds = dependsOnStageIds == null ? List.of() : List.copyOf(dependsOnStageIds);
    requirementIds = requirementIds == null ? List.of() : List.copyOf(requirementIds);
  }
}
