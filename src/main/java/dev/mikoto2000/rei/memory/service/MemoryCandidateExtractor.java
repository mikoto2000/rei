package dev.mikoto2000.rei.memory.service;

import dev.mikoto2000.rei.conversation.ConversationTurnStore.Turn;
import dev.mikoto2000.rei.memory.model.MemoryCandidate;
import java.util.List;

@FunctionalInterface
public interface MemoryCandidateExtractor {
  List<MemoryCandidate> extract(List<Turn> turns);

  default List<MemoryCandidate> extract(
      List<Turn> turns, dev.mikoto2000.rei.llm.ModelCallBudget budget) {
    if (budget == null) return extract(turns);
    if (budget.tokenLimitEnabled())
      throw new IllegalStateException("Memory extractor does not support token accounting");
    budget.run();
    return extract(turns);
  }
}
