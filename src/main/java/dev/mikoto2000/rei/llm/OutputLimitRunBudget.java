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
    default void recordTotalTokens(Integer tokens) { }
    default boolean tokenLimitEnabled() {return false;}
    default boolean tokenExhausted() {return false;}
    default boolean usageUnknown() {return false;}
  }
  private final LlmCallReservation reservation;

  private final int maxReplans;
  private final int maxLlmCalls;
  private int replans;
  private int llmCalls;
  private final long maxTotalTokens;
  private long totalTokens;
  private boolean usageUnknown;

  public OutputLimitRunBudget(int maxReplans, int maxLlmCalls) {
    this(maxReplans,maxLlmCalls,null);
  }
  public OutputLimitRunBudget(int maxReplans,int maxLlmCalls,LlmCallReservation reservation) {
    this(maxReplans,maxLlmCalls,reservation,0);
  }
  public OutputLimitRunBudget(int maxReplans,int maxLlmCalls,LlmCallReservation reservation,long maxTotalTokens) {
    if(maxTotalTokens<0)throw new IllegalArgumentException("Token limit must be nonnegative");
    this.maxTotalTokens=maxTotalTokens;
    this.maxReplans = Math.max(0, maxReplans);
    this.maxLlmCalls = Math.max(0, maxLlmCalls);
    this.reservation=reservation;
  }

  public LlmCallReservation sharedLlmReservation() {return reservation;}

  public synchronized boolean tryConsumeLlmCall() {
    if (llmCalls >= maxLlmCalls || tokenExhausted()) {
      return false;
    }
    if(reservation!=null&&!reservation.tryReserve())return false;
    llmCalls++;
    return true;
  }

  public synchronized boolean tryConsumeReplan() {
    if (replans >= maxReplans || remainingLlmCalls() <= 0) {
      return false;
    }
    replans++;
    return true;
  }

  public synchronized int replanCount() {
    return replans;
  }

  public synchronized int remainingLlmCalls() {
    if(tokenExhausted())return 0;
    int local=Math.max(0,maxLlmCalls-llmCalls);
    return reservation==null?local:Math.min(local,Math.max(0,reservation.remaining()));
  }

  public synchronized boolean hasRemainingLlmCalls() {
    return remainingLlmCalls() > 0;
  }
  public boolean tokenLimitEnabled(){return maxTotalTokens>0||(reservation!=null&&reservation.tokenLimitEnabled());}
  public synchronized long totalTokens(){return totalTokens;}
  public synchronized boolean usageUnknown(){return usageUnknown||(reservation!=null&&reservation.usageUnknown());}
  public synchronized boolean tokenExceeded(){return maxTotalTokens>0&&totalTokens>maxTotalTokens;}
  public synchronized boolean tokenExhausted(){return (maxTotalTokens>0&&(usageUnknown||totalTokens>=maxTotalTokens))||(reservation!=null&&reservation.tokenExhausted());}
  public synchronized void recordTotalTokens(Integer tokens) {
    if(!tokenLimitEnabled())return;
    if(tokens==null||tokens<=0)usageUnknown=true;
    else totalTokens=totalTokens>Long.MAX_VALUE-tokens?Long.MAX_VALUE:totalTokens+tokens;
    if(reservation!=null&&reservation.tokenLimitEnabled())reservation.recordTotalTokens(tokens);
  }
}
