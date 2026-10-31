package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import java.util.function.Supplier;
import org.jline.reader.LineReader;
import org.jline.reader.Widget;
import org.jline.reader.impl.LineReaderImpl;

/**
 * ターミナルでの対話に必要な活動中実行プロンプトを扱う。
 *
 * <p>Refreshes through a JLine widget, under JLine's own input lock, without editing the input
 * buffer.
 */
public final class ActiveRunPrompt implements AutoCloseable {
  /** 再確認。 */
  private static final String REFRESH = "rei-active-run-prompt";

  /** 読み取り元。 */
  private final LineReader reader;

  /** プロンプト。 */
  private final Supplier<String> prompt;

  /** 購読。 */
  private final ConversationInputRouter.Subscription subscription;

  /** prior初期化。 */
  private final Widget priorInit;

  /** 通常プロンプト。 */
  private volatile boolean normalPrompt;

  private AutoCloseable progressSubscription = () -> {};

  /** 指定された値や利用先を使って、ActiveRunPromptを初期化する。 */
  public ActiveRunPrompt(LineReader reader, ConversationInputRouter runs, Supplier<String> prompt) {
    this.reader = reader;
    this.prompt = prompt;
    priorInit = reader.getWidgets().get(LineReader.CALLBACK_INIT);
    reader
        .getWidgets()
        .put(
            REFRESH,
            () -> {
              if (normalPrompt && reader instanceof LineReaderImpl impl) {
                impl.setPrompt(prompt.get());
                impl.redisplay();
              }
              return true;
            });
    reader
        .getWidgets()
        .put(
            LineReader.CALLBACK_INIT,
            () -> {
              boolean initialized = priorInit == null || priorInit.apply();
              // Read the latest count after readLine initializes, closing the pre-read start/finish
              // race.
              if (normalPrompt && reader instanceof LineReaderImpl impl)
                impl.setPrompt(prompt.get());
              return initialized;
            });
    subscription = runs == null ? () -> {} : runs.onChange(this::refresh);
  }

  public ActiveRunPrompt(
      LineReader reader,
      ConversationInputRouter runs,
      Supplier<String> prompt,
      ActiveRunDisplay display) {
    this(reader, runs, prompt);
    if (display != null) progressSubscription = display.onProgressChange(this::refresh);
  }

  /** 必要な内容を参照できるよう、行を読み取る。 */
  public String readLine() {
    normalPrompt = true;
    try {
      return reader.readLine(prompt.get());
    } finally {
      normalPrompt = false;
    }
  }

  /** ターミナルでの対話に関する再確認を処理する。 */
  private void refresh() {
    if (!normalPrompt || !reader.isReading()) return;
    try {
      reader.callWidget(REFRESH);
    } catch (IllegalStateException endedRead) {
      /* The input finished between isReading and the JLine lock. */
    }
  }

  /** ターミナルでの対話で利用した資源や登録を解放し、利用を終了する。 */
  public void close() {
    subscription.close();
    try {
      progressSubscription.close();
    } catch (Exception error) {
      throw new IllegalStateException("Could not unsubscribe task progress", error);
    }
    reader.getWidgets().remove(REFRESH);
    if (priorInit == null) reader.getWidgets().remove(LineReader.CALLBACK_INIT);
    else reader.getWidgets().put(LineReader.CALLBACK_INIT, priorInit);
  }
}
