package dev.mikoto2000.rei.ui.shell;

import java.io.PrintWriter;

import org.jline.reader.LineReader;

/** JLine-aware output that preserves an input line being edited. */
public final class JLineShellEventOutput implements ShellEventOutput {
  private final LineReader reader;
  private final PrintWriter writer;
  private final StringBuilder pending = new StringBuilder();

  public JLineShellEventOutput(LineReader reader) {
    this.reader = reader;
    this.writer = reader.getTerminal().writer();
  }

  @Override
  public synchronized void print(String text) {
    if (reader.isReading()) {
      pending.append(text);
    } else {
      flushPendingToWriter();
      writer.print(text);
    }
  }

  @Override
  public synchronized void println(String text) {
    if (reader.isReading()) {
      reader.printAbove(pending + text);
      pending.setLength(0);
    } else {
      flushPendingToWriter();
      writer.println(text);
    }
  }

  @Override
  public synchronized void flush() {
    if (!reader.isReading()) writer.flush();
  }

  private void flushPendingToWriter() {
    if (!pending.isEmpty()) {
      writer.print(pending);
      pending.setLength(0);
    }
  }
}
