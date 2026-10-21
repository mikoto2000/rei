package dev.mikoto2000.rei.memory.service;

import dev.mikoto2000.rei.memory.model.LongTermMemory;
import dev.mikoto2000.rei.memory.model.MemoryCandidate;
import java.util.List;

/** 長期記憶の保存と検索に必要な記憶解決結果モデルを扱う。 */
@FunctionalInterface
public interface MemoryResolutionModel {
  /** 指定や設定を照合し、実際に利用する対象を確定する。 */
  MemoryResolution resolve(MemoryCandidate candidate, List<LongTermMemory> existing);

  /** 指定や設定を照合し、実際に利用する対象を確定する。 */
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
