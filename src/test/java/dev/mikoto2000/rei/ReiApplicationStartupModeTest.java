package dev.mikoto2000.rei;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.ui.tui.AgentTuiLauncher;

class ReiApplicationStartupModeTest {

  @Test
  void noOptionStartsShellAndTuiOptionStartsSharedLauncher() throws Exception {
    ReiApplication app = mock(ReiApplication.class);
    AgentTuiLauncher tui = mock(AgentTuiLauncher.class);

    ReiApplication.launch(StartupMode.SHELL, app, tui, new String[0]);
    verify(app).run(new String[0]);
    verify(tui, never()).run(null);

    ReiApplication.launch(StartupMode.TUI, app, tui, new String[] {"--tui"});
    verify(tui).run(null);
  }
}
