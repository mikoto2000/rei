package dev.mikoto2000.rei.ui.tui;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.jline.terminal.Terminal;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;

class AgentTuiCommandTest {

  @Test
  void delegatesShellEntryToSharedLauncherWithExistingTerminal() {
    AgentTuiLauncher launcher = mock(AgentTuiLauncher.class);
    Terminal terminal = mock(Terminal.class);

    LineReader reader = mock(LineReader.class);
    Completer completer = mock(Completer.class);
    new AgentTuiCommand(launcher).run(terminal, reader, completer);

    verify(launcher).run(terminal, reader, completer);
  }
}
