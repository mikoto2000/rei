package dev.mikoto2000.rei.llm;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

public class OutputLimitReplanParser {

  private final ObjectMapper objectMapper;

  public OutputLimitReplanParser() {
    this(new ObjectMapper());
  }

  OutputLimitReplanParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public OutputLimitReplanPlan parse(String plannerOutput, int maxSubgoals) {
    try {
      PlannerPlan plan = objectMapper.readValue(normalizeJsonObject(plannerOutput), PlannerPlan.class);
      return validate(plan, maxSubgoals);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to parse output limit replan", e);
    }
  }

  private OutputLimitReplanPlan validate(PlannerPlan plan, int maxSubgoals) {
    if (plan == null || plan.subgoals == null || plan.subgoals.isEmpty()) {
      throw new IllegalArgumentException("Planner subgoals must not be empty");
    }
    int limit = Math.max(1, maxSubgoals);
    List<OutputLimitReplanSubgoal> subgoals = new ArrayList<>();
    for (PlannerSubgoal subgoal : plan.subgoals) {
      if (subgoals.size() >= limit) {
        break;
      }
      String goal = trimToNull(subgoal == null ? null : subgoal.goal);
      if (goal == null) {
        throw new IllegalArgumentException("Planner subgoal goal must not be blank");
      }
      String id = trimToNull(subgoal.id);
      subgoals.add(new OutputLimitReplanSubgoal(id == null ? "subgoal-" + (subgoals.size() + 1) : id, goal));
    }
    if (subgoals.isEmpty()) {
      throw new IllegalArgumentException("Planner subgoals must not be empty");
    }
    String finalGoal = trimToNull(plan.finalGoal);
    return new OutputLimitReplanPlan(subgoals, finalGoal == null ? "サブゴールの結果を統合する" : finalGoal);
  }

  static String normalizeJsonObject(String response) {
    if (response == null || response.isBlank()) {
      return "{}";
    }
    String trimmed = response.trim();
    if (trimmed.startsWith("```")) {
      int firstNewline = trimmed.indexOf('\n');
      if (firstNewline >= 0) {
        trimmed = trimmed.substring(firstNewline + 1).trim();
      }
      int closingFence = trimmed.lastIndexOf("```");
      if (closingFence >= 0) {
        trimmed = trimmed.substring(0, closingFence).trim();
      }
    }
    int objectStart = trimmed.indexOf('{');
    int objectEnd = trimmed.lastIndexOf('}');
    if (objectStart >= 0 && objectEnd >= objectStart) {
      return trimmed.substring(objectStart, objectEnd + 1).trim();
    }
    return trimmed;
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static class PlannerPlan {
    public List<PlannerSubgoal> subgoals;
    public String finalGoal;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static class PlannerSubgoal {
    public String id;
    public String goal;
  }
}
