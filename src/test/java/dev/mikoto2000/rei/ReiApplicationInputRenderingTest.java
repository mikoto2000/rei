package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.mikoto2000.rei.core.command.RootCommand;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService;
import picocli.CommandLine;

class ReiApplicationInputRenderingTest {

  @Test
  void formatUserInputRendersLeftBarBlock() {
    ReiApplication app = newApp();
    String ls = System.lineSeparator();

    assertEquals(
        ls + "┌ User" + ls + "README を要約して" + ls + "└" + ls + ls,
        app.formatUserInput("README を要約して"));
  }

  @Test
  void formatUserInputPrefixesEveryLine() {
    ReiApplication app = newApp();
    String ls = System.lineSeparator();

    assertEquals(
        ls + "┌ User" + ls + "1行目" + ls + "2行目" + ls + "└" + ls + ls,
        app.formatUserInput("1行目\n2行目"));
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
        asyncVectorDocumentService);
  }
}
