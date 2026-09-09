package dev.mikoto2000.rei.core.execution;

/** Ownership adapter for background commands. It neither creates nor impersonates an AgentRun. */
public final class ExecutionScope implements AutoCloseable {
  private static final ThreadLocal<ActiveExecution> CURRENT=new ThreadLocal<>();
  private final ActiveExecution previous;
  private ExecutionScope(ActiveExecution execution) { previous=CURRENT.get(); CURRENT.set(execution); }
  public static ExecutionScope open(ActiveExecution execution) { return new ExecutionScope(execution); }
  public static ActiveExecution current() { return CURRENT.get(); }
  public void close() { if(previous==null) CURRENT.remove(); else CURRENT.set(previous); }
}
