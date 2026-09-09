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
      pending.append(text).append('\n');
      flushCompleteLines();
    } else {
      flushPendingToWriter();
      writer.println(text);
    }
  }

  @Override
  public synchronized void flush() {
    if (!reader.isReading()) {
      flushPendingToWriter();
      writer.flush();
    } else {
      flushCompleteLines();
    }
  }

  private void flushCompleteLines() {
    int width = reader.getTerminal().getWidth();
    if (width <= 0) width = 80;
    while (!pending.isEmpty()) {
      int newline = pending.indexOf("\n");
      int end = newline < 0 ? pending.length() : newline;
      int columns = 0;
      int wrap = -1;
      for (int offset = 0; offset < end;) {
        int codePoint = Character.codePointAt(pending, offset);
        int cells = codePoint == '\t' ? 8 - columns % 8 : Math.max(0, org.jline.utils.WCWidth.wcwidth(codePoint));
        if (columns + cells > width && offset > 0) { wrap = offset; break; }
        columns += cells;
        offset += Character.charCount(codePoint);
      }
      if (wrap > 0) {
        reader.printAbove(pending.substring(0, wrap));
        pending.delete(0, wrap);
      } else if (newline >= 0) {
        // printAbove supplies the newline. Never include the next incomplete line.
        int lineEnd = end > 0 && pending.charAt(end - 1) == '\r' ? end - 1 : end;
        reader.printAbove(pending.substring(0, lineEnd));
        pending.delete(0, newline + 1);
      } else {
        break; // Retain the incomplete physical line until more text or println arrives.
      }
    }
  }

  private void flushPendingToWriter() {
    if (!pending.isEmpty()) {
      writer.print(pending);
      pending.setLength(0);
    }
  }
}
