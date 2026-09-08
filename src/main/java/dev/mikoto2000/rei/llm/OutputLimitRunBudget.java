package dev.mikoto2000.rei.llm;

/**
 * Absolute run budget, shared by output-limit splitting and stagnation replanning.
 * Meaningful progress never replenishes it. The executor reserves each outer prompt/planner call;
 * the controlled model loop reserves every additional LLM/tool cycle before invoking the model.
 */
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

  public boolean hasRemainingLlmCalls() {
    return remainingLlmCalls() > 0;
  }
}
