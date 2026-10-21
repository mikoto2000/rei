package dev.mikoto2000.rei.core.completion;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * 入力候補の提示に必要な承認操作の選択肢行読み取り元を扱う。
 *
 * <p>Uses JLine's redraw area below the input rather than appending menus to scrollback.
 */
public final class ApprovalMenuLineReader extends LineReaderImpl {
  /** 操作の選択肢／状態。 */
  private String menu = "", status = "";

  /** 対話。 */
  private final boolean interactive;

  /** 承認投稿。 */
  private final Supplier<AttributedString> approvalPost = this::presentation;

  /** 入力候補の提示を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ApprovalMenuLineReader(Terminal terminal) throws IOException {
    this(terminal, ApprovalDisplayState.interactive(terminal));
  }

  /**
   * 入力候補の提示に必要な依存先や設定を受け取り、ApprovalMenuLineReaderを初期化する。
   *
   * <p>Explicit capability for in-memory terminal verification.
   */
  public ApprovalMenuLineReader(Terminal terminal, boolean interactive) throws IOException {
    super(terminal);
    this.interactive = interactive;
  }

  /** 入力候補の提示に関する承認操作の選択肢を処理する。 */
  public void approvalMenu(String text) {
    lock.lock();
    try {
      menu = text;
      post = approvalPost;
    } finally {
      lock.unlock();
    }
  }

  /** 保持している情報を初期状態へ戻すため、承認操作の選択肢を消去する。 */
  public void clearApprovalMenu() {
    lock.lock();
    try {
      if (post == approvalPost) post = null;
      menu = "";
    } finally {
      lock.unlock();
    }
  }

  /** 入力候補の提示に関する承認状態を処理する。 */
  public void approvalStatus(String text) {
    if (!interactive) text = "";
    lock.lock();
    try {
      if (status.equals(text)) return;
      status = text;
      if (isReading()) redisplay();
    } finally {
      lock.unlock();
    }
  }

  /** 入力候補の提示で用いるpresentationを返す。 */
  private AttributedString presentation() {
    var result =
        new AttributedStringBuilder()
            .append(status, AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold());
    if (!menu.isEmpty()) result.append(status.isEmpty() ? "" : "\n").append(menu);
    return result.toAttributedString();
  }

  /** 入力候補の提示で参照するDisplayed一時保持領域付きプロンプト一覧を返す。 */
  @Override
  public AttributedString getDisplayedBufferWithPrompts(List<AttributedString> secondaryPrompts) {
    lock.lock();
    Supplier<AttributedString> original = post;
    try {
      // readLine and completion replace post; compose at render time to retain status.
      if (!status.isEmpty())
        post =
            () -> {
              AttributedString other =
                  original == null || original == approvalPost ? null : original.get();
              var result = new AttributedStringBuilder().append(presentation());
              if (other != null) result.append("\n").append(other);
              return result.toAttributedString();
            };
      return super.getDisplayedBufferWithPrompts(secondaryPrompts);
    } finally {
      post = original;
      lock.unlock();
    }
  }
}
