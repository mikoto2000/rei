package dev.mikoto2000.rei.memory.service;

import dev.mikoto2000.rei.memory.model.*;
import java.util.List;

@FunctionalInterface
public interface MemoryResolutionModel {
  MemoryResolution resolve(MemoryCandidate candidate, List<LongTermMemory> existing);

  default MemoryResolution resolve(
      MemoryCandidate candidate,
      List<LongTermMemory> existing,
      dev.mikoto2000.rei.llm.ModelCallBudget budget) {
    if (budget == null) return resolve(candidate, existing);
    if (budget.tokenLimitEnabled())
      throw new IllegalStateException("Memory resolver does not support token accounting");
    budget.run();
    return resolve(candidate, existing);
  }
}
