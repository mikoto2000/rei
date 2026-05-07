package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.mikoto2000.rei.core.command.RootCommand;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.sound.ChatResponseNarrator;
import dev.mikoto2000.rei.sound.SoundNotificationService;
import dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService;
import picocli.CommandLine;

class ReiApplicationInputRenderingTest {

  @Test
  void buildPromptPrefixesTimeIn24HourFormat() {
    ReiApplication app = newAppWithFixedTime("gpt-5.4", LocalTime.of(9, 42));

    assertEquals("09:42 gpt-5.4> ", app.buildPrompt());
  }

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
    return newApp("test-model");
  }

  private ReiApplication newApp(String modelName) {
    AsyncVectorDocumentService asyncVectorDocumentService = Mockito.mock(AsyncVectorDocumentService.class);
    when(asyncVectorDocumentService.hasActiveEmbeddings()).thenReturn(false);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    when(modelHolderService.get()).thenReturn(modelName);
    return new ReiApplication(
        Mockito.mock(RootCommand.class),
        CommandLine.defaultFactory(),
        modelHolderService,
        Mockito.mock(EscCancellationMonitor.class),
        Mockito.mock(CommandCancellationService.class),
        asyncVectorDocumentService,
        Mockito.mock(SoundNotificationService.class),
        Mockito.mock(ChatResponseNarrator.class));
  }

  private ReiApplication newAppWithFixedTime(String modelName, LocalTime fixedTime) {
    AsyncVectorDocumentService asyncVectorDocumentService = Mockito.mock(AsyncVectorDocumentService.class);
    when(asyncVectorDocumentService.hasActiveEmbeddings()).thenReturn(false);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    when(modelHolderService.get()).thenReturn(modelName);
    return new ReiApplication(
        Mockito.mock(RootCommand.class),
        CommandLine.defaultFactory(),
        modelHolderService,
        Mockito.mock(EscCancellationMonitor.class),
        Mockito.mock(CommandCancellationService.class),
        asyncVectorDocumentService,
        Mockito.mock(SoundNotificationService.class),
        Mockito.mock(ChatResponseNarrator.class)) {
      @Override
      LocalTime now() {
        return fixedTime;
      }
    };
  }
}
