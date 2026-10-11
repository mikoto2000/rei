package dev.mikoto2000.rei.launcher;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BackendModeTest {
  @Test void preservesLegacyDefaultDuringMigration() {
    assertEquals(BackendMode.LEGACY_SHELL,BackendMode.parse(new String[0]));
    assertEquals(BackendMode.SERVER,BackendMode.parse(new String[]{"--mode=server"}));
    assertEquals(BackendMode.LEGACY_SHELL,BackendMode.parse(new String[]{"--mode","legacy-shell","-p","."}));
  }
  @Test void refusesAmbiguousOrUnimplementedModesBeforeSpring() {
    for (String[] args : new String[][]{{"--mode=auto"},{"--mode=client"},{"--mode"},
        {"--mode=server","--mode=legacy-shell"},{"--mode=unknown"}})
      assertThrows(IllegalArgumentException.class, () -> BackendMode.parse(args));
  }
}
