package dev.mikoto2000.rei.llm;

import java.util.List;

public record OutputLimitReplanPlan(List<OutputLimitReplanSubgoal> subgoals, String finalGoal) {
  public OutputLimitReplanPlan {
    subgoals = List.copyOf(subgoals);
  }
}
