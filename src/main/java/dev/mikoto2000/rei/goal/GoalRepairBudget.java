package dev.mikoto2000.rei.goal;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation;
/** A slice of the same durable reservation, leaving calls for a later repair attempt. */
public final class GoalRepairBudget implements LlmCallReservation {
 private final LlmCallReservation parent;private final int allowance,reserved;private int used;
 public GoalRepairBudget(LlmCallReservation parent,int reserveCalls){
  if(parent==null||reserveCalls<0||reserveCalls>10)throw new IllegalArgumentException("Repair reserve must be 0..10 calls");
  this.parent=parent;int remaining=Math.max(0,parent.remaining());reserved=Math.min(reserveCalls,Math.max(0,remaining-1));allowance=remaining-reserved;
 }
 public synchronized boolean tryReserve(){if(used>=allowance||!parent.tryReserve())return false;used++;return true;}
 public synchronized int remaining(){return Math.max(0,Math.min(allowance-used,parent.remaining()));}
 public synchronized boolean boundaryReached(){return reserved>0&&used>=allowance&&parent.remaining()>0&&!parent.tokenExhausted()&&!parent.usageUnknown();}
 public boolean tokenLimitEnabled(){return parent.tokenLimitEnabled();}
 public boolean tokenExhausted(){return parent.tokenExhausted();}
 public boolean usageUnknown(){return parent.usageUnknown();}
 public void recordTotalTokens(Integer tokens){parent.recordTotalTokens(tokens);}
}
