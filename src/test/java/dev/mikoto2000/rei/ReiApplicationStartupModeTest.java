package dev.mikoto2000.rei;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class ReiApplicationStartupModeTest {

  @Test
  void allStartupArgumentsUseTheShellEntryPoint() throws Exception {
    ReiApplication app = mock(ReiApplication.class);

    ReiApplication.launch(app, new String[0]);
    verify(app).run(new String[0]);

    ReiApplication.launch(app, new String[] {"--tui"});
    verify(app).run(new String[] {"--tui"});
  }
}
