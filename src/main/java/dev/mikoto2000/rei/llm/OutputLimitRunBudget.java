package dev.mikoto2000.rei.llm;

public class OutputLimitRunBudget {

  private final int maxReplans;
  private final int maxLlmCalls;
  private int replans;
  private int llmCalls;

  public OutputLimitRunBudget(int maxReplans, int maxLlmCalls) {
    this.maxReplans = Math.max(0, maxReplans);
    this.maxLlmCalls = Math.max(0, maxLlmCalls);
  }

  public boolean tryConsumeLlmCall() {
    if (llmCalls >= maxLlmCalls) {
      return false;
    }
    llmCalls++;
    return true;
  }

  public boolean tryConsumeReplan() {
    if (replans >= maxReplans || remainingLlmCalls() <= 0) {
      return false;
    }
    replans++;
    return true;
  }

  public int replanCount() {
    return replans;
  }

  public int remainingLlmCalls() {
    return Math.max(0, maxLlmCalls - llmCalls);
  }
}
