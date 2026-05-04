package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.mikoto2000.rei.core.command.RootCommand;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.sound.ChatResponseNarrator;
import dev.mikoto2000.rei.sound.SoundNotificationService;
import dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService;
import picocli.CommandLine;

class ReiApplicationColorOutputTest {

    /**
     * Creates a mock ANSI-capable Terminal whose writer() returns a PrintWriter
     * wrapping a StringWriter, so output can be captured.
     */
    private Terminal mockAnsiTerminal() {
        Terminal terminal = mock(Terminal.class);
        // Report ANSI capability
        when(terminal.getType()).thenReturn(Terminal.TYPE_DUMB_COLOR);
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        when(terminal.writer()).thenReturn(pw);
        return terminal;
    }

    /**
     * Returns the StringWriter that backs the terminal's PrintWriter,
     * allowing callers to inspect what was written.
     */
    private StringWriter captureTerminalOutput(Terminal terminal) {
        // The PrintWriter wraps a StringWriter; we need to get it back.
        // Since we set it up ourselves, we create a fresh pair and re-stub.
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        when(terminal.writer()).thenReturn(pw);
        return sw;
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

    /**
     * Requirement 2.1: printUserInput(String, Terminal) must write to terminal.writer()
     * at least once via print() and flush().
     */
    @Test
    void printUserInputWritesToTerminalWriter() {
        ReiApplication app = newApp();
        Terminal terminal = mock(Terminal.class);
        when(terminal.getType()).thenReturn(Terminal.TYPE_DUMB_COLOR);
        PrintWriter mockWriter = mock(PrintWriter.class);
        when(terminal.writer()).thenReturn(mockWriter);

        app.printUserInput("テスト", terminal);

        verify(mockWriter, atLeastOnce()).print(anyString());
        verify(mockWriter, atLeastOnce()).flush();
    }

    /**
     * Requirement 2.3: When the terminal does not support ANSI colors (dumb terminal),
     * printUserInput(String, Terminal) must output plain text without ANSI escape sequences,
     * and the output must equal formatUserInput(input).
     */
    @Test
    void dumbTerminalOutputsPlainText() throws IOException {
        ReiApplication app = newApp();

        ByteArrayInputStream inputStream = new ByteArrayInputStream(new byte[0]);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        Terminal dumbTerminal = new org.jline.terminal.impl.DumbTerminal(inputStream, outputStream);

        app.printUserInput("テスト入力", dumbTerminal);
        dumbTerminal.writer().flush();

        String output = outputStream.toString();

        assertFalse(output.contains("\u001B["), "dumb terminal output must not contain ANSI escape sequences");
        assertEquals(app.formatUserInput("テスト入力"), output);
    }
}
