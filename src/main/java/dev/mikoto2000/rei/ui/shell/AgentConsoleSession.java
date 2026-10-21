package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.core.chat.AgentRunScope;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * ターミナルでの対話に必要なエージェントコンソール会話を扱う。
 *
 * <p>Installs one thread-aware console adapter for the Shell lifetime, instead of muting the whole
 * process per run.
 */
public final class AgentConsoleSession implements AutoCloseable {
  /** 前回出力先。 */
  private final PrintStream previousOut = System.out;

  /** 前回Err。 */
  private final PrintStream previousErr = System.err;

  /** routed出力先。 */
  private final PrintStream routedOut = route(previousOut);

  /** 振り分けたエラーの出力先。 */
  private final PrintStream routedErr = route(previousErr);

  /** 既定の状態でAgentConsoleSessionを初期化する。 */
  public AgentConsoleSession() {
    System.setOut(routedOut);
    System.setErr(routedErr);
  }

  /** ターミナルでの対話で用いる受付経路を返す。 */
  static PrintStream route(PrintStream original) {
    return new PrintStream(
        new OutputStream() {
          /** 指定された内容を書き込み先へ反映する。 */
          @Override
          public void write(int value) {
            if (unscoped()) original.write(value);
          }

          /** 指定された内容を書き込み先へ反映する。 */
          @Override
          public void write(byte[] bytes, int offset, int length) {
            if (unscoped()) original.write(bytes, offset, length);
          }

          /** ターミナルでの対話に関する保存の確定を処理する。 */
          @Override
          public void flush() {
            original.flush();
          }
        },
        true,
        original.charset());
  }

  /** ターミナルでの対話において、unscopedに該当するか判定する。 */
  private static boolean unscoped() {
    return AgentRunScope.current() == null
        && dev.mikoto2000.rei.core.execution.ExecutionScope.current() == null;
  }

  /** ターミナルでの対話で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  public void close() {
    routedOut.flush();
    routedErr.flush();
    if (System.out == routedOut) System.setOut(previousOut);
    if (System.err == routedErr) System.setErr(previousErr);
  }
}
