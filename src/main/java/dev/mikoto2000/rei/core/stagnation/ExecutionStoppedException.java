package dev.mikoto2000.rei.core.stagnation;

/** Control signal, distinguished from model/transport failures at the run boundary. */
public class ExecutionStoppedException extends RuntimeException {
  public enum Reason { STAGNATED, LLM_CALL_BUDGET_EXCEEDED, REPLAN_BUDGET_EXCEEDED }
  private final Reason reason;
  public ExecutionStoppedException(Reason reason) { super(reason.name()); this.reason = reason; }
  public Reason reason() { return reason; }
}
