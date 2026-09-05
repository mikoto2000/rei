package dev.mikoto2000.rei.event;

import java.util.List;

public record ContextBudgetTrimmedPayload(int inputBudget, int totalTokens, List<String> dropped)
    implements AgentEventPayload {
  public ContextBudgetTrimmedPayload {
    dropped = dropped == null ? List.of() : List.copyOf(dropped);
  }
}
