package dev.mikoto2000.rei.llm;

/** Optional accounting on the existing before-model callback. */
public interface ModelCallBudget extends Runnable {
  boolean tokenLimitEnabled();
  void recordTotalTokens(Integer tokens);
}
