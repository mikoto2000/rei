package dev.mikoto2000.rei.goal;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/** Host validation. Existing contracts cannot be removed or relaxed by a plan proposal. */
public final class GoalStagePlanValidator {
  private GoalStagePlanValidator() {}

  public static void validate(GoalRepository.Goal goal, List<GoalStageDefinition> plan) {
    if (plan == null || plan.isEmpty() || plan.size() > 32)
      throw new IllegalArgumentException("Expected 1..32 stages");
    var byId = new LinkedHashMap<String, GoalStageDefinition>();
    var requirements = new HashSet<String>();
    if (goal.completion() != null)
      goal.completion().requirements().forEach(r -> requirements.add(r.id()));
    for (var item : plan) {
      if (item == null
          || item.id() == null
          || !item.id().matches("[A-Za-z][A-Za-z0-9_-]{0,63}")
          || byId.putIfAbsent(item.id(), item) != null
          || item.objective() == null
          || item.objective().isBlank()
          || item.objective().length() > 4096
          || item.criteria().isEmpty()
          || item.criteria().size() > 16
          || item.dependsOnStageIds().size() > 32
          || new HashSet<>(item.dependsOnStageIds()).size() != item.dependsOnStageIds().size()
          || !requirements.containsAll(item.requirementIds()))
        throw new IllegalArgumentException("Invalid stage definition or requirement reference");
      for (var criterion : item.criteria()) {
        // Use the existing strict parser and path validation, including predicate bounds.
        GoalCompletionGate.criterion(criterion);
        if (!criterion.jsonCriterion()
            && (criterion.sha256() == null || !criterion.sha256().matches("[a-f0-9]{64}")))
          throw new IllegalArgumentException("Stage requires exact SHA or JSON predicate");
      }
      if (item.completion() != null) GoalCompletionGate.validateDefinition(item.completion());
    }
    var covered = new HashSet<String>();
    plan.forEach(s -> covered.addAll(s.requirementIds()));
    if (goal.completion() != null
        && goal.completion().requirements().stream()
            .anyMatch(r -> r.required() && !covered.contains(r.id())))
      throw new IllegalArgumentException("Stage plan must cover all mandatory Goal requirements");
    for (var item : plan) {
      if (!byId.keySet().containsAll(item.dependsOnStageIds())
          || item.parentStageId() != null && !byId.containsKey(item.parentStageId()))
        throw new IllegalArgumentException("Unknown stage reference");
      var ancestors = new HashSet<String>();
      var current = item;
      while (current.parentStageId() != null) {
        if (!ancestors.add(current.id()) || ancestors.size() > 3)
          throw new IllegalArgumentException("Stage parent cycle or depth limit");
        current = byId.get(current.parentStageId());
        if (current == null) throw new IllegalArgumentException("Unknown parent stage reference");
      }
    }
    var visited = new HashSet<String>();
    boolean progress;
    do {
      progress = false;
      for (var item : plan) {
        var prerequisites = new HashSet<>(item.dependsOnStageIds());
        plan.stream()
            .filter(s -> item.id().equals(s.parentStageId()))
            .forEach(s -> prerequisites.add(s.id()));
        if (!visited.contains(item.id()) && visited.containsAll(prerequisites)) {
          visited.add(item.id());
          progress = true;
        }
      }
    } while (progress);
    if (visited.size() != plan.size()) throw new IllegalArgumentException("Stage dependency cycle");
  }

  public static void preserve(List<GoalStageDefinition> previous, List<GoalStageDefinition> next) {
    if (!new HashSet<>(next).containsAll(previous))
      throw new IllegalArgumentException(
          "Existing stage contracts are immutable; no condition relaxation");
    var previousIds =
        previous.stream().map(GoalStageDefinition::id).collect(java.util.stream.Collectors.toSet());
    if (next.stream()
        .filter(s -> !previousIds.contains(s.id()))
        .anyMatch(s -> s.parentStageId() == null))
      throw new IllegalArgumentException("Plan revisions may only add bounded child stages");
  }
}
