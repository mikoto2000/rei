package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.CommandCompletionNotificationPolicy;
import dev.mikoto2000.rei.core.service.CommandUserInputDisplayPolicy;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.ui.shell.RootCommand;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;
import dev.mikoto2000.rei.ui.shell.sound.SoundNotificationService;
import dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** れいアプリケーションターミナルへの表示先判断基準の結果と状態の契約を検証するテスト。 */
class ReiApplicationShellOutputPolicyTest {
  /** れいの起動で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void suppressesLegacyChatStdoutButKeepsSlashCommandOutput() {
    ReiApplication app =
        new ReiApplication(
            mock(RootCommand.class),
            CommandLine.defaultFactory(),
            mock(ModelHolderService.class),
            mock(EscCancellationMonitor.class),
            mock(CommandCancellationService.class),
            new CommandCompletionNotificationPolicy(),
            new CommandUserInputDisplayPolicy(),
            mock(AsyncVectorDocumentService.class),
            mock(SoundNotificationService.class),
            mock(ChatResponseNarrator.class),
            mock(AgentEventBus.class));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream previous = System.out;
    try (PrintStream capture = new PrintStream(bytes)) {
      System.setOut(capture);
      app.executeWithOutputPolicy(new CommandLine(new Root()), "chat");
      app.executeWithOutputPolicy(new CommandLine(new Root()), "status");
    } finally {
      System.setOut(previous);
    }
    assertEquals("slash-output" + System.lineSeparator(), bytes.toString());
  }

  /** 検証条件を再現するための作業ルート。 */
  @CommandLine.Command(subcommands = {Chat.class, Status.class})
  static class Root {}

  /** 検証条件を再現するためのチャット。 */
  @CommandLine.Command(name = "chat")
  static class Chat implements Runnable {
    /** 検証用に、れいの起動の操作を実行する。 */
    public void run() {
      System.out.println("legacy-chat-output");
    }
  }

  /** 検証条件を再現するための状態。 */
  @CommandLine.Command(name = "status")
  static class Status implements Runnable {
    /** 検証用に、れいの起動の操作を実行する。 */
    public void run() {
      System.out.println("slash-output");
    }
  }
}
