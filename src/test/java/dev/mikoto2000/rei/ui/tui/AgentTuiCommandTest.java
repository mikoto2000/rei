package dev.mikoto2000.rei.ui.tui;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;

class AgentTuiCommandTest {

  @Test
  void delegatesShellEntryToSharedLauncherWithExistingTerminal() {
    AgentTuiLauncher launcher = mock(AgentTuiLauncher.class);
    Terminal terminal = mock(Terminal.class);

    new AgentTuiCommand(launcher).run(terminal);

    verify(launcher).run(terminal);
  }
}
