package dev.mikoto2000.rei;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;

/**
 * れいの起動に必要な前面コンソールを扱う。
 *
 * <p>Foreground Backend lifetime follows its console; detached Backends do not use this reader.
 */
final class ForegroundConsole {
  /** 設定項目の識別名。 */
  static final String PROPERTY = "rei.console-exit.enabled";

  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ForegroundConsole() {}

  /** れいの起動の操作を実行する。 */
  static void run(org.springframework.context.ConfigurableApplicationContext context)
      throws IOException {
    try (var terminal = ReiApplication.terminalBuilder(System.out).build()) {
      awaitExit(terminal, context::isActive);
    } finally {
      context.close();
    }
  }

  /** 必要な内容を参照できるよう、コマンド入力を読み取る。 */
  static int readCommandInput(NonBlockingReader reader, long timeout) throws IOException {
    int input = reader.read(timeout);
    if (input == 3) throw new UserInterruptException("");
    if (input == 4 || input == -1) throw new EndOfFileException();
    return input;
  }

  /** 必要な状態が整ってから先へ進むため、終了を待つ。 */
  static void awaitExit(Terminal terminal, BooleanSupplier running) throws IOException {
    var interrupted = new AtomicBoolean();
    var priorHandler = terminal.handle(Terminal.Signal.INT, signal -> interrupted.set(true));
    var attributes = terminal.enterRawMode();
    try {
      terminal.writer().println("Backend started. Ctrl+D / Ctrl+C to stop.");
      terminal.writer().flush();
      while (running.getAsBoolean() && !interrupted.get()) {
        int input = terminal.reader().read(200);
        if (input == -1 || input == 3 || input == 4) break;
      }
    } finally {
      terminal.setAttributes(attributes);
      terminal.handle(Terminal.Signal.INT, priorHandler);
    }
  }
}
