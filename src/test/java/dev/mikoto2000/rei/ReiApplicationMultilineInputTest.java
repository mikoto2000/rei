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

class ReiApplicationMultilineInputTest {

  @Test
  void readPossiblyMultilineInput_returnsSingleLineAsIs() {
    ReiApplication app = newApp();
    LineReader reader = Mockito.mock(LineReader.class);

    assertEquals("hello", app.readPossiblyMultilineInput("hello", reader));
  }

  @Test
  void readPossiblyMultilineInput_joinsContinuationLines() {
    ReiApplication app = newApp();
    LineReader reader = Mockito.mock(LineReader.class);
    when(reader.readLine("...> ")).thenReturn("world");

    assertEquals("hello" + System.lineSeparator() + "world", app.readPossiblyMultilineInput("hello\\", reader));
  }

  @Test
  void readPossiblyMultilineInput_supportsMultipleContinuationLines() {
    ReiApplication app = newApp();
    LineReader reader = Mockito.mock(LineReader.class);
    when(reader.readLine("...> ")).thenReturn("line2\\", "line3");

    assertEquals(
        "line1" + System.lineSeparator() + "line2" + System.lineSeparator() + "line3",
        app.readPossiblyMultilineInput("line1\\", reader));
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
