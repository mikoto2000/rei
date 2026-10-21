package dev.mikoto2000.rei.core.chat;

/**
 * 会話の実行を継続できない実行待機中の状況を伝える例外。
 *
 * <p>Control flow after the tool batch is durably saved; never a failed or replayable tool.
 */
public final class RunWaitingException extends RuntimeException {
  /** 既定の状態でRunWaitingExceptionを初期化する。 */
  public RunWaitingException() {
    super("Run suspended for a saved continuation", null, false, false);
  }
}
