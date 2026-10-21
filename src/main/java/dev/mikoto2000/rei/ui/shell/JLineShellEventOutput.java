package dev.mikoto2000.rei.ui.shell;

import java.io.PrintWriter;
import org.jline.reader.LineReader;

/**
 * ターミナルでの対話に必要なJLineシェルイベント出力を扱う。
 *
 * <p>JLine-aware output that preserves an input line being edited.
 */
public final class JLineShellEventOutput implements ShellEventOutput {
  /** 読み取り元。 */
  private final LineReader reader;

  /** 書き込み先。 */
  private final PrintWriter writer;

  /** 待機中。 */
  private final StringBuilder pending = new StringBuilder();

  /** 承認操作の選択肢。 */
  private dev.mikoto2000.rei.core.completion.ApprovalActionMenu approvalMenu;

  /** ターミナルでの対話で使用する承認操作の選択肢を設定する。 */
  public void setApprovalMenu(dev.mikoto2000.rei.core.completion.ApprovalActionMenu menu) {
    approvalMenu = menu;
  }

  /** ターミナルでの対話に関する承認操作一覧を処理する。 */
  @Override
  public void approvalActions(String project, String session, String id) {
    if (approvalMenu != null) approvalMenu.offer(project, session, id);
  }

  /** 指定された値や利用先を使って、JLineShellEventOutputを初期化する。 */
  public JLineShellEventOutput(LineReader reader) {
    this.reader = reader;
    this.writer = reader.getTerminal().writer();
  }

  /** ターミナルでの対話に関する承認必須を処理する。 */
  @Override
  public synchronized void approvalRequired(String message) {
    if (dev.mikoto2000.rei.core.completion.ApprovalDisplayState.interactive(reader.getTerminal()))
      printBlock(dev.mikoto2000.rei.core.completion.ApprovalNotice.format(message));
    else {
      // Do not ask JLine to redraw a forced terminal type when actual streams are redirected.
      flushPendingToWriter();
      writer.println(dev.mikoto2000.rei.core.completion.ApprovalNotice.text(message));
      writer.flush();
    }
  }

  /**
   * 利用者に結果を伝えるため、まとまりを出力する。
   *
   * <p>Print one styled, newline-delimited block without interleaving stream chunks or damaging the
   * prompt.
   */
  public synchronized void printBlock(org.jline.utils.AttributedString block) {
    // Let JLine decide whether a prompt is active under its own lock, avoiding a
    // readLine-start race. Do not count ANSI bytes as columns in flushCompleteLines().
    var combined = new org.jline.utils.AttributedStringBuilder().append(pending).append(block);
    pending.setLength(0);
    reader.printAbove(combined.toAttributedString());
  }

  /** 利用者に結果を伝えるため、JLineシェルイベント出力を出力する。 */
  @Override
  public synchronized void print(String text) {
    if (reader.isReading()) {
      pending.append(text);
    } else {
      flushPendingToWriter();
      writer.print(text);
    }
  }

  /** ターミナルでの対話に関する改行付きの出力を処理する。 */
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

  /** ターミナルでの対話に関する保存の確定を処理する。 */
  @Override
  public synchronized void flush() {
    if (!reader.isReading()) {
      flushPendingToWriter();
      writer.flush();
    } else {
      flushCompleteLines();
    }
  }

  /** ターミナルでの対話に関する保存の確定完了行一覧を処理する。 */
  private void flushCompleteLines() {
    int width = reader.getTerminal().getWidth();
    if (width <= 0) width = 80;
    while (!pending.isEmpty()) {
      int newline = pending.indexOf("\n");
      int end = newline < 0 ? pending.length() : newline;
      int columns = 0;
      int wrap = -1;
      for (int offset = 0; offset < end; ) {
        int codePoint = Character.codePointAt(pending, offset);
        int cells =
            codePoint == '\t'
                ? 8 - columns % 8
                : Math.max(0, org.jline.utils.WCWidth.wcwidth(codePoint));
        if (columns + cells > width && offset > 0) {
          wrap = offset;
          break;
        }
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

  /** ターミナルでの対話に関する保存の確定待機中終点書き込み先を処理する。 */
  private void flushPendingToWriter() {
    if (!pending.isEmpty()) {
      writer.print(pending);
      pending.setLength(0);
    }
  }
}
