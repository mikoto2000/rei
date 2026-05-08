package dev.mikoto2000.rei;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.mikoto2000.rei.core.command.RootCommand;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.sound.ChatResponseNarrator;
import dev.mikoto2000.rei.sound.SoundNotificationService;
import dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService;
import picocli.CommandLine;

/**
 * Tests that executeInterruptibly() calls soundNotificationService.notify()
 * in its finally block after command execution.
 */
class ReiApplicationCommandNotificationTest {

    private SoundNotificationService soundNotificationService;
    private EscCancellationMonitor escCancellationMonitor;
    private ChatResponseNarrator chatResponseNarrator;
    private Terminal terminal;
    private ExecutorService commandExecutor;

    @BeforeEach
    void setUp() throws IOException {
        soundNotificationService = mock(SoundNotificationService.class);
        // EscCancellationMonitor: immediately return the future result without blocking
        escCancellationMonitor = mock(EscCancellationMonitor.class);
        chatResponseNarrator = mock(ChatResponseNarrator.class);
        terminal = TerminalBuilder.builder().dumb(true).build();
        commandExecutor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() throws IOException {
        commandExecutor.shutdownNow();
        terminal.close();
    }

    private ReiApplication newApp() {
        AsyncVectorDocumentService asyncVectorDocumentService = Mockito.mock(AsyncVectorDocumentService.class);
        when(asyncVectorDocumentService.hasActiveEmbeddings()).thenReturn(false);
        return new ReiApplication(
                Mockito.mock(RootCommand.class),
                CommandLine.defaultFactory(),
                Mockito.mock(ModelHolderService.class),
                escCancellationMonitor,
                Mockito.mock(CommandCancellationService.class),
                asyncVectorDocumentService,
                soundNotificationService,
                chatResponseNarrator);
    }

    @Test
    void notifiesOnNormalCompletion() throws IOException {
        // Arrange: a stub picocli command that returns exit code 0
        @CommandLine.Command(name = "stub")
        class StubCommand implements Runnable {
            @Override
            public void run() {
                // normal completion, exit code 0
            }
        }

        CommandLine cmd = new CommandLine(new StubCommand());

        // EscCancellationMonitor.await() should just return the future result
        when(escCancellationMonitor.await(
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenAnswer(invocation -> {
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
     * Requirement 1.2: Even when a RuntimeException is thrown during command execution,
     * the finally block must still call soundNotificationService.notify().
     */
    @Test
    void notifiesEvenWhenRuntimeExceptionIsThrown() throws IOException {
        // Arrange: a stub picocli command (content doesn't matter since await() will throw)
        @CommandLine.Command(name = "stub")
        class StubCommand implements Runnable {
            @Override
            public void run() {
                // normal stub
            }
        }

        CommandLine cmd = new CommandLine(new StubCommand());

        // Simulate an unexpected RuntimeException thrown from escCancellationMonitor.await()
        // (e.g. the command itself threw a RuntimeException that propagated through ExecutionException)
        when(escCancellationMonitor.await(
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenThrow(new RuntimeException("コマンド実行中に予期しない例外が発生しました"));

        ReiApplication app = newApp();

        // Act: executeInterruptibly should propagate the RuntimeException
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
                app.executeInterruptibly(cmd, terminal, commandExecutor, "stub"));

        // Assert: notify must still have been called with the completion message
        // (because it is in the finally block)
        verify(soundNotificationService).notify("コマンド実行が完了しました");
    }

    /**
     * Requirement 2.1: When wasNarrated() returns true, the command completion notification
     * must be skipped.
     */
    @Test
    void skipsCommandCompletionNotificationWhenNarrated() throws IOException {
        // Arrange: a stub picocli command
        @CommandLine.Command(name = "stub")
        class StubCommand implements Runnable {
            @Override
            public void run() {
                // normal completion
            }
        }

        CommandLine cmd = new CommandLine(new StubCommand());

        when(escCancellationMonitor.await(
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenAnswer(invocation -> {
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
     * Requirement 2.2: When wasNarrated() returns false, the command completion notification
     * must be executed as usual.
     */
    @Test
    void executesCommandCompletionNotificationWhenNotNarrated() throws IOException {
        // Arrange: a stub picocli command
        @CommandLine.Command(name = "stub")
        class StubCommand implements Runnable {
            @Override
            public void run() {
                // normal completion
            }
        }

        CommandLine cmd = new CommandLine(new StubCommand());

        when(escCancellationMonitor.await(
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenAnswer(invocation -> {
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
    @Test
    void skipsCommandCompletionNotificationForModelCommand() throws IOException {
        @CommandLine.Command(name = "stub")
        class StubCommand implements Runnable {
            @Override
            public void run() {
            }
        }

        CommandLine cmd = new CommandLine(new StubCommand());
        when(escCancellationMonitor.await(
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenAnswer(invocation -> {
                    var future = invocation.<java.util.concurrent.Future<Integer>>getArgument(0);
                    return future.get();
                });
        when(chatResponseNarrator.wasNarrated()).thenReturn(false);
        ReiApplication app = newApp();

        app.executeInterruptibly(cmd, terminal, commandExecutor, "model");

        verify(soundNotificationService, never()).notify("ã‚³ãƒžãƒ³ãƒ‰å®Ÿè¡ŒãŒå®Œäº†ã—ã¾ã—ãŸ");
    }

    @Test
    void skipsCommandCompletionNotificationForModelsCommand() throws IOException {
        @CommandLine.Command(name = "stub")
        class StubCommand implements Runnable {
            @Override
            public void run() {
            }
        }

        CommandLine cmd = new CommandLine(new StubCommand());
        when(escCancellationMonitor.await(
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenAnswer(invocation -> {
                    var future = invocation.<java.util.concurrent.Future<Integer>>getArgument(0);
                    return future.get();
                });
        when(chatResponseNarrator.wasNarrated()).thenReturn(false);
        ReiApplication app = newApp();

        app.executeInterruptibly(cmd, terminal, commandExecutor, "models");

        verify(soundNotificationService, never()).notify("ã‚³ãƒžãƒ³ãƒ‰å®Ÿè¡ŒãŒå®Œäº†ã—ã¾ã—ãŸ");
    }
}
