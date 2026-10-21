package dev.mikoto2000.rei.core.completion;

import java.time.Instant;
import org.jline.terminal.Terminal;
import org.jline.terminal.spi.SystemStream;
import org.jline.terminal.spi.TerminalExt;

/**
 * 入力候補の提示に必要な承認表示状態を扱う。
 *
 * <p>Presentation eligibility only; does not decide or change approval state.
 */
public final class ApprovalDisplayState {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ApprovalDisplayState() {}

  /** 後続の処理で判断できるよう、未完了の対象を返す。 */
  public static boolean pending(String status, Instant expires, String continuation, Instant now) {
    return "PENDING".equals(status)
        && expires != null
        && now.isBefore(expires)
        && ("WAITING_APPROVAL".equals(continuation) || "UNAVAILABLE".equals(continuation));
  }

  /** 入力候補の提示において、対話に該当するか判定する。 */
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
