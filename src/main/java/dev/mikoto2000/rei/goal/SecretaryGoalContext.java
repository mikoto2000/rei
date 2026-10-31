package dev.mikoto2000.rei.goal;

import dev.mikoto2000.rei.secretary.SecretaryContext;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Read the existing Goal state and verified completion gate; never starts or resumes a Goal. */
@Component
@ConditionalOnProperty(name = "rei.secretary.enabled", havingValue = "true")
public final class SecretaryGoalContext implements SecretaryContext {
  private final GoalRepository goals;

  public SecretaryGoalContext(GoalRepository goals) {
    this.goals = goals;
  }

  @Override
  public List<Item> context(String project, LocalDate date, ZoneId zone) {
    return goals.list(project).stream()
        .map(
            goal -> {
              String state =
                  switch (goal.status()) {
                    case "COMPLETED" -> "COMPLETED";
                    case "CANCELLED" -> "CANCELLED";
                    case "RUNNING" -> "IN_PROGRESS";
                    case "BLOCKED", "FAILED" -> "BLOCKED";
                    case "WAITING_APPROVAL" -> "WAITING";
                    case "READY", "PAUSED" -> "PENDING";
                    default -> "UNKNOWN";
                  };
              var updated =
                  goals.history(project, goal.id()).stream()
                      .map(GoalRepository.History::timestamp)
                      .max(java.util.Comparator.naturalOrder())
                      .orElse(null);
              var refs =
                  goal.currentRunId() == null || goal.currentRunId().isBlank()
                      ? List.<String>of()
                      : List.of("run:" + goal.currentRunId());
              return new Item(
                  "goal",
                  goal.id(),
                  goal.objective(),
                  state,
                  goal.reason(),
                  Reliability.FACT,
                  refs,
                  new Observation(
                      "goal:" + goal.id(),
                      updated,
                      state,
                      !state.equals("UNKNOWN"),
                      null,
                      null,
                      "GOAL"));
            })
        .toList();
  }
}
