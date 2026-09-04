package dev.mikoto2000.rei.computer;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
@Disabled("Manual Windows GUI smoke test. Enable only in an interactive desktop session.")
class WindowsUiAutomationBackendIT {

  @Test
  void observesFocusedWindowWhenWindowsDesktopIsAvailable() {
    WindowsUiAutomationBackend backend = new WindowsUiAutomationBackend();

    ComputerObservation observation = backend.observe(new ComputerObservationRequest(1, 20, true, true));

    assertNotNull(observation);
  }
}
