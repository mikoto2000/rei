package dev.mikoto2000.rei.core.completion;

import java.time.Instant;
import org.jline.terminal.Terminal;
import org.jline.terminal.spi.*;

/** Presentation eligibility only; does not decide or change approval state. */
public final class ApprovalDisplayState {
  private ApprovalDisplayState() {}

  public static boolean pending(String status, Instant expires, String continuation, Instant now) {
    return "PENDING".equals(status)
        && expires != null
        && now.isBefore(expires)
        && ("WAITING_APPROVAL".equals(continuation) || "UNAVAILABLE".equals(continuation));
  }

  public static boolean interactive(Terminal terminal) {
    if (terminal == null
        || Terminal.TYPE_DUMB.equals(terminal.getType())
        || Terminal.TYPE_DUMB_COLOR.equals(terminal.getType())) return false;
    try {
      return terminal instanceof TerminalExt ext
          && ext.getSystemStream() != null
          && ext.getProvider() != null
          && ext.getProvider().isSystemStream(SystemStream.Input)
          && ext.getProvider().isSystemStream(ext.getSystemStream());
    } catch (RuntimeException | LinkageError unavailable) {
      return false;
    }
  }
}
