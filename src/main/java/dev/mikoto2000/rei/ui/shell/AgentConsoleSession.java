package dev.mikoto2000.rei.ui.shell;

import java.io.OutputStream;
import java.io.PrintStream;
import dev.mikoto2000.rei.core.chat.AgentRunScope;

/** Installs one thread-aware console adapter for the Shell lifetime, instead of muting the whole process per run. */
public final class AgentConsoleSession implements AutoCloseable {
  private final PrintStream previousOut = System.out;
  private final PrintStream previousErr = System.err;
  private final PrintStream routedOut = route(previousOut);
  private final PrintStream routedErr = route(previousErr);
  public AgentConsoleSession() { System.setOut(routedOut); System.setErr(routedErr); }
  static PrintStream route(PrintStream original) {
    return new PrintStream(new OutputStream() {
      @Override public void write(int value) { if (unscoped()) original.write(value); }
      @Override public void write(byte[] bytes, int offset, int length) {
        if (unscoped()) original.write(bytes, offset, length);
      }
      @Override public void flush() { original.flush(); }
    }, true, original.charset());
  }
  private static boolean unscoped() {
    return AgentRunScope.current()==null && dev.mikoto2000.rei.core.execution.ExecutionScope.current()==null;
  }
  @Override public void close() {
    routedOut.flush(); routedErr.flush();
    if (System.out == routedOut) System.setOut(previousOut);
    if (System.err == routedErr) System.setErr(previousErr);
  }
}
