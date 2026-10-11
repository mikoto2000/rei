package dev.mikoto2000.rei.terminal;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class LauncherOptionsTest {
  @Test void defaultsToAutoAndRejectsUnknownModesBeforeBackendStarts() {
    assertEquals("auto",LauncherOptions.parse(new String[0]).mode());
    assertThrows(IllegalArgumentException.class,()->LauncherOptions.parse(new String[]{"--mode","unknown"}));
    assertThrows(IllegalArgumentException.class,()->LauncherOptions.parse(new String[]{"--mode=client","--mode=auto"}));
  }
  @Test void serverStopRequiresExplicitConfirmation() {
    assertFalse(LauncherOptions.parse(new String[]{"server","stop"}).confirmed());
    assertTrue(LauncherOptions.parse(new String[]{"server","stop","--yes"}).confirmed());
  }
}
