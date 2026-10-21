package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.CommandCompletionNotificationPolicy;
import dev.mikoto2000.rei.core.service.CommandUserInputDisplayPolicy;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.ui.shell.RootCommand;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;
import dev.mikoto2000.rei.ui.shell.sound.SoundNotificationService;
import dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.jline.reader.EndOfFileException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import picocli.CommandLine;

/** れいアプリケーション終了確認の結果と状態の契約を検証するテスト。 */
class ReiApplicationExitConfirmationTest {

  /** 有効を返すことを確認する。 */
  @Test
  void confirmExitIfNeededReturnsTrueWhenNoEmbedIsRunning() {
    ReiApplication app = newApp(false);

    assertTrue(
        app.confirmExitIfNeeded(
            prompt -> {
              throw new AssertionError("confirmation should not be requested");
            }));
  }

  /** れいの起動の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void confirmExitIfNeededCancelsExitWhenUserDeclines() {
    ReiApplication app = newApp(true);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      assertFalse(app.confirmExitIfNeeded(prompt -> "n"));
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("終了をキャンセルしました"));
  }

  /** 有効を返すことを確認する。 */
  @Test
  void confirmExitIfNeededReturnsTrueWhenUserConfirms() {
    ReiApplication app = newApp(true);

    assertTrue(app.confirmExitIfNeeded(prompt -> "y"));
  }

  /** れいの起動の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void confirmExitIfNeededCancelsExitWhenConfirmationIsInterrupted() {
    ReiApplication app = newApp(true);

    assertFalse(
        app.confirmExitIfNeeded(
            prompt -> {
              throw new EndOfFileException();
            }));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private ReiApplication newApp(boolean activeEmbeddings) {
    AsyncVectorDocumentService asyncVectorDocumentService =
        Mockito.mock(AsyncVectorDocumentService.class);
    when(asyncVectorDocumentService.hasActiveEmbeddings()).thenReturn(activeEmbeddings);
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
