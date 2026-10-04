package dev.mikoto2000.rei.llm;

/**
 * Absolute run budget, shared by output-limit splitting and stagnation replanning.
 * Meaningful progress never replenishes it. The executor reserves each outer prompt/planner call;
 * the controlled model loop reserves every additional LLM/tool cycle before invoking the model.
 */
public class OutputLimitRunBudget {
  /** Optional durable parent budget. Reservations precede every model call. */
  public interface LlmCallReservation {
    boolean tryReserve();
    int remaining();
  }
  private final LlmCallReservation reservation;

  private final int maxReplans;
  private final int maxLlmCalls;
  private int replans;
  private int llmCalls;

  public OutputLimitRunBudget(int maxReplans, int maxLlmCalls) {
    this(maxReplans,maxLlmCalls,null);
  }
  public OutputLimitRunBudget(int maxReplans,int maxLlmCalls,LlmCallReservation reservation) {
    this.maxReplans = Math.max(0, maxReplans);
    this.maxLlmCalls = Math.max(0, maxLlmCalls);
    this.reservation=reservation;
  }

  public boolean tryConsumeLlmCall() {
    if (llmCalls >= maxLlmCalls) {
      return false;
    }
    if(reservation!=null&&!reservation.tryReserve())return false;
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
    int local=Math.max(0,maxLlmCalls-llmCalls);
    return reservation==null?local:Math.min(local,Math.max(0,reservation.remaining()));
  }

  public boolean hasRemainingLlmCalls() {
    return remainingLlmCalls() > 0;
  }
}
