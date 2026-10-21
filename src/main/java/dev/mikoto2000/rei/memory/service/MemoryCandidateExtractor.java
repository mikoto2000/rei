package dev.mikoto2000.rei.memory.service;

import dev.mikoto2000.rei.conversation.ConversationTurnStore.Turn;
import dev.mikoto2000.rei.memory.model.MemoryCandidate;
import java.util.List;

/** 記憶候補に必要な情報を入力から取り出す。 */
@FunctionalInterface
public interface MemoryCandidateExtractor {
  /** 受け取った内容から、後続の処理に必要な情報を取り出す。 */
  List<MemoryCandidate> extract(List<Turn> turns);

  /** 受け取った内容から、後続の処理に必要な情報を取り出す。 */
  default List<MemoryCandidate> extract(
      List<Turn> turns, dev.mikoto2000.rei.llm.ModelCallBudget budget) {
    if (budget == null) return extract(turns);
    if (budget.tokenLimitEnabled())
      throw new IllegalStateException("Memory extractor does not support token accounting");
    budget.run();
    return extract(turns);
  }
}
