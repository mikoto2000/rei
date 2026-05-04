package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.jline.reader.EndOfFileException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.mikoto2000.rei.core.command.RootCommand;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.sound.SoundNotificationService;
import dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService;
import picocli.CommandLine;

class ReiApplicationExitConfirmationTest {

  @Test
  void confirmExitIfNeededReturnsTrueWhenNoEmbedIsRunning() {
    ReiApplication app = newApp(false);

    assertTrue(app.confirmExitIfNeeded(prompt -> {
      throw new AssertionError("confirmation should not be requested");
    }));
  }

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

  @Test
  void confirmExitIfNeededReturnsTrueWhenUserConfirms() {
    ReiApplication app = newApp(true);

    assertTrue(app.confirmExitIfNeeded(prompt -> "y"));
  }

  @Test
  void confirmExitIfNeededCancelsExitWhenConfirmationIsInterrupted() {
    ReiApplication app = newApp(true);

    assertFalse(app.confirmExitIfNeeded(prompt -> {
      throw new EndOfFileException();
    }));
  }

  private ReiApplication newApp(boolean activeEmbeddings) {
    AsyncVectorDocumentService asyncVectorDocumentService = Mockito.mock(AsyncVectorDocumentService.class);
    when(asyncVectorDocumentService.hasActiveEmbeddings()).thenReturn(activeEmbeddings);
    return new ReiApplication(
        Mockito.mock(RootCommand.class),
        CommandLine.defaultFactory(),
        Mockito.mock(ModelHolderService.class),
        Mockito.mock(EscCancellationMonitor.class),
        Mockito.mock(CommandCancellationService.class),
        asyncVectorDocumentService,
        Mockito.mock(SoundNotificationService.class));
  }
}
