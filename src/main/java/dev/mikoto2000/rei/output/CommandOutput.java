package dev.mikoto2000.rei.output;

import java.io.PrintWriter;
import java.util.function.Consumer;

/** コマンドにテキスト出力とフラッシュだけを提供する。 */
public final class CommandOutput {
  private final Consumer<String> printRaw;
  private final Consumer<String> printText;
  private final Consumer<Object> printValue;
  private final Runnable flushOutput;

  private CommandOutput(
      Consumer<String> printRaw,
      Consumer<String> printText,
      Consumer<Object> printValue,
      Runnable flushOutput) {
    this.printRaw = printRaw;
    this.printText = printText;
    this.printValue = printValue;
    this.flushOutput = flushOutput;
  }

  /** 指定された出力先を使い、未指定なら呼び出し元の既定出力に委ねる。 */
  public static CommandOutput from(PrintWriter writer) {
    return writer == null
        ? null
        : new CommandOutput(writer::print, writer::println, writer::println, writer::flush);
  }

  /** 改行を加えずにテキストを出力する。 */
  public void print(String text) {
    printRaw.accept(text);
  }

  /** テキストを一行出力する。 */
  public void println(String text) {
    printText.accept(text);
  }

  /** 値を一行出力する。 */
  public void println(Object value) {
    printValue.accept(value);
  }

  /** 未出力の内容を反映する。 */
  public void flush() {
    flushOutput.run();
  }
}
