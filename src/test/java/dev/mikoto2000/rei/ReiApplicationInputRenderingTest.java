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
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import picocli.CommandLine;

/** れいアプリケーション入力表示の結果と状態の契約を検証するテスト。 */
class ReiApplicationInputRenderingTest {

  /** れいの起動で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void buildPromptPrefixesTimeIn24HourFormat() {
    ReiApplication app = newAppWithFixedTime("gpt-5.4", LocalTime.of(9, 42));

    assertEquals("09:42 gpt-5.4> ", app.buildPrompt());
  }

  /** れいの起動で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void formatUserInputRendersLeftBarBlock() {
    ReiApplication app = newAppWithFixedTime("test-model", LocalTime.of(20, 19, 20));
    String ls = System.lineSeparator();

    assertEquals(
        ls + "┌ User (20:19:20)" + ls + "README を要約して" + ls + "└" + ls + ls,
        app.formatUserInput("README を要約して"));
  }

  /** れいの起動で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void formatUserInputPrefixesEveryLine() {
    ReiApplication app = newAppWithFixedTime("test-model", LocalTime.of(20, 19, 20));
    String ls = System.lineSeparator();

    assertEquals(
        ls + "┌ User (20:19:20)" + ls + "1行目" + ls + "2行目" + ls + "└" + ls + ls,
        app.formatUserInput("1行目\n2行目"));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private ReiApplication newApp() {
    return newApp("test-model");
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private ReiApplication newApp(String modelName) {
    AsyncVectorDocumentService asyncVectorDocumentService =
        Mockito.mock(AsyncVectorDocumentService.class);
    when(asyncVectorDocumentService.hasActiveEmbeddings()).thenReturn(false);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    when(modelHolderService.get()).thenReturn(modelName);
    return new ReiApplication(
        Mockito.mock(RootCommand.class),
        CommandLine.defaultFactory(),
        modelHolderService,
        Mockito.mock(EscCancellationMonitor.class),
        Mockito.mock(CommandCancellationService.class),
        new CommandCompletionNotificationPolicy(),
        new CommandUserInputDisplayPolicy(),
        asyncVectorDocumentService,
        Mockito.mock(SoundNotificationService.class),
        Mockito.mock(ChatResponseNarrator.class),
        Mockito.mock(dev.mikoto2000.rei.event.AgentEventBus.class));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private ReiApplication newAppWithFixedTime(String modelName, LocalTime fixedTime) {
    AsyncVectorDocumentService asyncVectorDocumentService =
        Mockito.mock(AsyncVectorDocumentService.class);
    when(asyncVectorDocumentService.hasActiveEmbeddings()).thenReturn(false);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    when(modelHolderService.get()).thenReturn(modelName);
    return new ReiApplication(
        Mockito.mock(RootCommand.class),
        CommandLine.defaultFactory(),
        modelHolderService,
        Mockito.mock(EscCancellationMonitor.class),
        Mockito.mock(CommandCancellationService.class),
        new CommandCompletionNotificationPolicy(),
        new CommandUserInputDisplayPolicy(),
        asyncVectorDocumentService,
        Mockito.mock(SoundNotificationService.class),
        Mockito.mock(ChatResponseNarrator.class),
        Mockito.mock(dev.mikoto2000.rei.event.AgentEventBus.class)) {
      /** {@code now} の条件を再現するための補助情報を返す。 */
      @Override
      LocalTime now() {
        return fixedTime;
      }
    };
  }
}
