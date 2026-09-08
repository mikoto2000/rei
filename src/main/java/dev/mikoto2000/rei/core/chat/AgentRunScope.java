package dev.mikoto2000.rei.core.chat;

/** Lexical adapter for existing synchronous services; explicitly re-entered at async boundaries. */
public final class AgentRunScope implements AutoCloseable {
  private static final ThreadLocal<AgentRunContext> CURRENT = new ThreadLocal<>();
  private final AgentRunContext previous;
  private AgentRunScope(AgentRunContext context) {
    previous = CURRENT.get();
    if (context == null) CURRENT.remove(); else CURRENT.set(context);
  }
  public static AgentRunScope open(AgentRunContext context) { return new AgentRunScope(context); }
  public static AgentRunContext current() { return CURRENT.get(); }
  public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
