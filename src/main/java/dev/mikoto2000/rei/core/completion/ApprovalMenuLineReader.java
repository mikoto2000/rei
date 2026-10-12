package dev.mikoto2000.rei.core.completion;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/** Uses JLine's redraw area below the input rather than appending menus to scrollback. */
public final class ApprovalMenuLineReader extends LineReaderImpl {
  private String menu = "", status = "";
  private final boolean interactive;
  private final Supplier<AttributedString> approvalPost = this::presentation;
  public ApprovalMenuLineReader(Terminal terminal) throws IOException { this(terminal, ApprovalDisplayState.interactive(terminal)); }
  /** Explicit capability for in-memory terminal verification. */
  public ApprovalMenuLineReader(Terminal terminal, boolean interactive) throws IOException { super(terminal); this.interactive = interactive; }
  public void approvalMenu(String text) {
    lock.lock();
    try { menu = text; post = approvalPost; }
    finally { lock.unlock(); }
  }
  public void clearApprovalMenu() {
    lock.lock();
    try { if (post == approvalPost) post = null; menu = ""; }
    finally { lock.unlock(); }
  }
  public void approvalStatus(String text) {
    if (!interactive) text = "";
    lock.lock();
    try {
      if (status.equals(text)) return;
      status = text;
      if (isReading()) redisplay();
    } finally { lock.unlock(); }
  }
  private AttributedString presentation() {
    var result = new AttributedStringBuilder().append(status, AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold());
    if (!menu.isEmpty()) result.append(status.isEmpty() ? "" : "\n").append(menu);
    return result.toAttributedString();
  }
  @Override public AttributedString getDisplayedBufferWithPrompts(List<AttributedString> secondaryPrompts) {
    lock.lock();
    Supplier<AttributedString> original = post;
    try {
      // readLine and completion replace post; compose at render time to retain status.
      if (!status.isEmpty()) post = () -> {
        AttributedString other = original == null || original == approvalPost ? null : original.get();
        var result = new AttributedStringBuilder().append(presentation());
        if (other != null) result.append("\n").append(other);
        return result.toAttributedString();
      };
      return super.getDisplayedBufferWithPrompts(secondaryPrompts);
    } finally { post = original; lock.unlock(); }
  }
}
