package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.CommandCompletionNotificationPolicy;
import dev.mikoto2000.rei.core.service.CommandUserInputDisplayPolicy;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.ui.shell.RootCommand;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;
import dev.mikoto2000.rei.ui.shell.sound.SoundNotificationService;
import dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService;
import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import picocli.CommandLine;

/** れいアプリケーションPaste動作モードの結果と状態の契約を検証するテスト。 */
class ReiApplicationPasteModeTest {

  /** 終了日時状態表示行を読み取ることを確認する。 */
  @Test
  void readPasteBlock_readsUntilDotLine() {
    ReiApplication app = newApp();
    LineReader reader = Mockito.mock(LineReader.class);
    when(reader.readLine("paste> ")).thenReturn("line1", "line2", ".");

    assertEquals("line1" + System.lineSeparator() + "line2", app.readPasteBlock(reader));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private ReiApplication newApp() {
    AsyncVectorDocumentService asyncVectorDocumentService =
        Mockito.mock(AsyncVectorDocumentService.class);
    when(asyncVectorDocumentService.hasActiveEmbeddings()).thenReturn(false);
    return new ReiApplication(
        Mockito.mock(RootCommand.class),
        CommandLine.defaultFactory(),
        Mockito.mock(ModelHolderService.class),
        Mockito.mock(EscCancellationMonitor.class),
        Mockito.mock(CommandCancellationService.class),
        new CommandCompletionNotificationPolicy(),
        new CommandUserInputDisplayPolicy(),
        asyncVectorDocumentService,
        Mockito.mock(SoundNotificationService.class),
        Mockito.mock(ChatResponseNarrator.class),
        Mockito.mock(dev.mikoto2000.rei.event.AgentEventBus.class));
  }
}
