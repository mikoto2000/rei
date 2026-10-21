package dev.mikoto2000.rei;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.CommandCompletionNotificationPolicy;
import dev.mikoto2000.rei.core.service.CommandUserInputDisplayPolicy;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.ui.shell.RootCommand;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;
import dev.mikoto2000.rei.ui.shell.sound.SoundNotificationService;
import dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import picocli.CommandLine;

/**
 * れいアプリケーションコマンド通知の結果と状態の契約を検証するテスト。
 *
 * <p>Tests that executeInterruptibly() calls soundNotificationService.notify() in its finally block
 * after command execution.
 */
class ReiApplicationCommandNotificationTest {

  /** 検証に使う音通知通知サービス。 */
  private SoundNotificationService soundNotificationService;

  /** 検証に使うescキャンセル排他制御。 */
  private EscCancellationMonitor escCancellationMonitor;

  /** 検証に使うチャット応答読み上げの案内。 */
  private ChatResponseNarrator chatResponseNarrator;

  /** 検証に使う終端。 */
  private Terminal terminal;

  /** 検証に使うコマンド実行先。 */
  private ExecutorService commandExecutor;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setUp() throws IOException {
    soundNotificationService = mock(SoundNotificationService.class);
    // EscCancellationMonitor: immediately return the future result without blocking
    escCancellationMonitor = mock(EscCancellationMonitor.class);
    chatResponseNarrator = mock(ChatResponseNarrator.class);
    terminal =
        new DumbTerminal(
            java.io.InputStream.nullInputStream(), java.io.OutputStream.nullOutputStream());
    commandExecutor = Executors.newSingleThreadExecutor();
  }

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void tearDown() throws IOException {
    commandExecutor.shutdownNow();
    terminal.close();
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
        escCancellationMonitor,
        Mockito.mock(CommandCancellationService.class),
        new CommandCompletionNotificationPolicy(),
        new CommandUserInputDisplayPolicy(),
        asyncVectorDocumentService,
        soundNotificationService,
        chatResponseNarrator,
        Mockito.mock(dev.mikoto2000.rei.event.AgentEventBus.class));
  }

  /** れいの起動で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void notifiesOnNormalCompletion() throws IOException {
    // Arrange: a stub picocli command that returns exit code 0
    /** 検証条件を再現するための検証用の応答コマンド。 */
    @CommandLine.Command(name = "stub")
    class StubCommand implements Runnable {
      /** 検証用に、れいの起動の操作を実行する。 */
      @Override
      public void run() {
        // normal completion, exit code 0
      }
    }

    CommandLine cmd = new CommandLine(new StubCommand());

    // EscCancellationMonitor.await() should just return the future result
    when(escCancellationMonitor.await(Mockito.any(), Mockito.any(), Mockito.any()))
        .thenAnswer(
            invocation -> {
              var future = invocation.<java.util.concurrent.Future<Integer>>getArgument(0);
              return future.get();
            });

    ReiApplication app = newApp();

    // Act
    app.executeInterruptibly(cmd, terminal, commandExecutor, "stub");

    // Assert: notify must have been called with the completion message
    verify(soundNotificationService).notify("コマンド実行が完了しました");
  }

  /**
   * れいの起動で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。
   *
   * <p>Requirement 1.2: Even when a RuntimeException is thrown during command execution, the
   * finally block must still call soundNotificationService.notify().
   */
  @Test
  void notifiesEvenWhenRuntimeExceptionIsThrown() throws IOException {
    // Arrange: a stub picocli command (content doesn't matter since await() will throw)
    /** 検証条件を再現するための検証用の応答コマンド。 */
    @CommandLine.Command(name = "stub")
    class StubCommand implements Runnable {
      /** 検証用に、れいの起動の操作を実行する。 */
      @Override
      public void run() {
        // normal stub
      }
    }

    CommandLine cmd = new CommandLine(new StubCommand());

    // Simulate an unexpected RuntimeException thrown from escCancellationMonitor.await()
    // (e.g. the command itself threw a RuntimeException that propagated through ExecutionException)
    when(escCancellationMonitor.await(Mockito.any(), Mockito.any(), Mockito.any()))
        .thenThrow(new RuntimeException("コマンド実行中に予期しない例外が発生しました"));

    ReiApplication app = newApp();

    // Act: executeInterruptibly should propagate the RuntimeException
    org.junit.jupiter.api.Assertions.assertThrows(
        RuntimeException.class,
        () -> app.executeInterruptibly(cmd, terminal, commandExecutor, "stub"));

    // Assert: notify must still have been called with the completion message
    // (because it is in the finally block)
    verify(soundNotificationService).notify("コマンド実行が完了しました");
  }

  /**
   * れいの起動で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。
   *
   * <p>Requirement 2.1: When wasNarrated() returns true, the command completion notification must
   * be skipped.
   */
  @Test
  void skipsCommandCompletionNotificationWhenNarrated() throws IOException {
    // Arrange: a stub picocli command
    /** 検証条件を再現するための検証用の応答コマンド。 */
    @CommandLine.Command(name = "stub")
    class StubCommand implements Runnable {
      /** 検証用に、れいの起動の操作を実行する。 */
      @Override
      public void run() {
        // normal completion
      }
    }

    CommandLine cmd = new CommandLine(new StubCommand());

    when(escCancellationMonitor.await(Mockito.any(), Mockito.any(), Mockito.any()))
        .thenAnswer(
            invocation -> {
              var future = invocation.<java.util.concurrent.Future<Integer>>getArgument(0);
              return future.get();
            });

    // wasNarrated() returns true → notification should be skipped
    when(chatResponseNarrator.wasNarrated()).thenReturn(true);

    ReiApplication app = newApp();

    // Act
    app.executeInterruptibly(cmd, terminal, commandExecutor, "stub");

    // Assert: notify must NOT have been called with the completion message
    verify(soundNotificationService, never()).notify("コマンド実行が完了しました");
  }

  /**
   * れいの起動で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。
   *
   * <p>Requirement 2.2: When wasNarrated() returns false, the command completion notification must
   * be executed as usual.
   */
  @Test
  void executesCommandCompletionNotificationWhenNotNarrated() throws IOException {
    // Arrange: a stub picocli command
    /** 検証条件を再現するための検証用の応答コマンド。 */
    @CommandLine.Command(name = "stub")
    class StubCommand implements Runnable {
      /** 検証用に、れいの起動の操作を実行する。 */
      @Override
      public void run() {
        // normal completion
      }
    }

    CommandLine cmd = new CommandLine(new StubCommand());

    when(escCancellationMonitor.await(Mockito.any(), Mockito.any(), Mockito.any()))
        .thenAnswer(
            invocation -> {
              var future = invocation.<java.util.concurrent.Future<Integer>>getArgument(0);
              return future.get();
            });

    // wasNarrated() returns false → notification should be executed
    when(chatResponseNarrator.wasNarrated()).thenReturn(false);

    ReiApplication app = newApp();

    // Act
    app.executeInterruptibly(cmd, terminal, commandExecutor, "stub");

    // Assert: notify must have been called with the completion message
    verify(soundNotificationService).notify("コマンド実行が完了しました");
  }

  /** れいの起動で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void skipsCommandCompletionNotificationForModelCommand() throws IOException {
    /** 検証条件を再現するための検証用の応答コマンド。 */
    @CommandLine.Command(name = "stub")
    class StubCommand implements Runnable {
      /** 検証用に、れいの起動の操作を実行する。 */
      @Override
      public void run() {}
    }

    CommandLine cmd = new CommandLine(new StubCommand());
    when(escCancellationMonitor.await(Mockito.any(), Mockito.any(), Mockito.any()))
        .thenAnswer(
            invocation -> {
              var future = invocation.<java.util.concurrent.Future<Integer>>getArgument(0);
              return future.get();
            });
    when(chatResponseNarrator.wasNarrated()).thenReturn(false);
    ReiApplication app = newApp();

    app.executeInterruptibly(cmd, terminal, commandExecutor, "model");

    verify(soundNotificationService, never()).notify("コマンド実行が完了しました");
  }

  /** れいの起動で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void skipsCommandCompletionNotificationForModelsCommand() throws IOException {
    /** 検証条件を再現するための検証用の応答コマンド。 */
    @CommandLine.Command(name = "stub")
    class StubCommand implements Runnable {
      /** 検証用に、れいの起動の操作を実行する。 */
      @Override
      public void run() {}
    }

    CommandLine cmd = new CommandLine(new StubCommand());
    when(escCancellationMonitor.await(Mockito.any(), Mockito.any(), Mockito.any()))
        .thenAnswer(
            invocation -> {
              var future = invocation.<java.util.concurrent.Future<Integer>>getArgument(0);
              return future.get();
            });
    when(chatResponseNarrator.wasNarrated()).thenReturn(false);
    ReiApplication app = newApp();

    app.executeInterruptibly(cmd, terminal, commandExecutor, "models");

    verify(soundNotificationService, never()).notify("コマンド実行が完了しました");
  }

  /** れいの起動で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void skipsCommandCompletionNotificationForProjectCommand() throws IOException {
    /** 検証条件を再現するための検証用の応答コマンド。 */
    @CommandLine.Command(name = "stub")
    class StubCommand implements Runnable {
      /** 検証用に、れいの起動の操作を実行する。 */
      @Override
      public void run() {}
    }

    CommandLine cmd = new CommandLine(new StubCommand());
    when(escCancellationMonitor.await(Mockito.any(), Mockito.any(), Mockito.any()))
        .thenAnswer(
            invocation -> {
              var future = invocation.<java.util.concurrent.Future<Integer>>getArgument(0);
              return future.get();
            });
    when(chatResponseNarrator.wasNarrated()).thenReturn(false);
    ReiApplication app = newApp();

    app.executeInterruptibly(cmd, terminal, commandExecutor, "project", "list");

    verify(soundNotificationService, never()).notify("コマンド実行が完了しました");
  }
}
