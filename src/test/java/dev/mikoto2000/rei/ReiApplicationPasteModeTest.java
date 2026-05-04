package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.mikoto2000.rei.core.command.RootCommand;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.sound.ChatResponseNarrator;
import dev.mikoto2000.rei.sound.SoundNotificationService;
import dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService;
import picocli.CommandLine;

class ReiApplicationPasteModeTest {

  @Test
  void readPasteBlock_readsUntilDotLine() {
    ReiApplication app = newApp();
    LineReader reader = Mockito.mock(LineReader.class);
    when(reader.readLine("paste> ")).thenReturn("line1", "line2", ".");

    assertEquals("line1" + System.lineSeparator() + "line2", app.readPasteBlock(reader));
  }

  private ReiApplication newApp() {
    AsyncVectorDocumentService asyncVectorDocumentService = Mockito.mock(AsyncVectorDocumentService.class);
    when(asyncVectorDocumentService.hasActiveEmbeddings()).thenReturn(false);
    return new ReiApplication(
        Mockito.mock(RootCommand.class),
        CommandLine.defaultFactory(),
        Mockito.mock(ModelHolderService.class),
        Mockito.mock(EscCancellationMonitor.class),
        Mockito.mock(CommandCancellationService.class),
        asyncVectorDocumentService,
        Mockito.mock(SoundNotificationService.class),
        Mockito.mock(ChatResponseNarrator.class));
  }
}
