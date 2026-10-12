package dev.mikoto2000.rei.core.completion;

import java.io.IOException;
import java.util.function.Supplier;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

/** Uses JLine's redraw area below the input rather than appending menus to scrollback. */
public final class ApprovalMenuLineReader extends LineReaderImpl {
  private Supplier<AttributedString> approvalPost;
  public ApprovalMenuLineReader(Terminal terminal) throws IOException { super(terminal); }
  public void approvalMenu(String text) {
    approvalPost = () -> new AttributedString(text);
    post = approvalPost;
  }
  public void clearApprovalMenu() {
    if (post == approvalPost) post = null;
    approvalPost = null;
  }
}
