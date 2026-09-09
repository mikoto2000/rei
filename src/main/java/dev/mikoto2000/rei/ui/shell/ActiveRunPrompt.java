package dev.mikoto2000.rei.ui.shell;

import java.util.function.Supplier;
import org.jline.reader.*;
import org.jline.reader.impl.LineReaderImpl;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;

/** Refreshes through a JLine widget, under JLine's own input lock, without editing the input buffer. */
public final class ActiveRunPrompt implements AutoCloseable {
  private static final String REFRESH = "rei-active-run-prompt";
  private final LineReader reader;
  private final Supplier<String> prompt;
  private final ConversationInputRouter.Subscription subscription;
  private final Widget priorInit;
  private volatile boolean normalPrompt;
  public ActiveRunPrompt(LineReader reader, ConversationInputRouter runs, Supplier<String> prompt) {
    this.reader = reader; this.prompt = prompt;
    priorInit = reader.getWidgets().get(LineReader.CALLBACK_INIT);
    reader.getWidgets().put(REFRESH, () -> {
      if (normalPrompt && reader instanceof LineReaderImpl impl) {
        impl.setPrompt(prompt.get());
        impl.redisplay();
      }
      return true;
    });
    reader.getWidgets().put(LineReader.CALLBACK_INIT, () -> {
      if (priorInit != null) priorInit.apply();
      // Read the latest count after readLine initializes, closing the pre-read start/finish race.
      if (normalPrompt && reader instanceof LineReaderImpl impl) impl.setPrompt(prompt.get());
      return true;
    });
    subscription = runs == null ? () -> {} : runs.onChange(this::refresh);
  }
  public String readLine() {
    normalPrompt = true;
    try { return reader.readLine(prompt.get()); }
    finally { normalPrompt = false; }
  }
  private void refresh() {
    if (!normalPrompt || !reader.isReading()) return;
    try { reader.callWidget(REFRESH); }
    catch (IllegalStateException endedRead) { /* The input finished between isReading and the JLine lock. */ }
  }
  public void close() {
    subscription.close();
    reader.getWidgets().remove(REFRESH);
    if (priorInit == null) reader.getWidgets().remove(LineReader.CALLBACK_INIT);
    else reader.getWidgets().put(LineReader.CALLBACK_INIT, priorInit);
  }
}
