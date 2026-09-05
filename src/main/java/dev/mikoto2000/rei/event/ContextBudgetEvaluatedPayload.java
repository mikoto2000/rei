package dev.mikoto2000.rei.event;

import java.util.List;

public record ContextBudgetEvaluatedPayload(int inputBudget, int totalTokens, List<String> included,
    List<String> dropped) implements AgentEventPayload {
  public ContextBudgetEvaluatedPayload {
    included = included == null ? List.of() : List.copyOf(included);
    dropped = dropped == null ? List.of() : List.copyOf(dropped);
  }
}
