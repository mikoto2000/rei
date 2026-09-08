package dev.mikoto2000.rei.core.datasource;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReiDataDirectoryTest {
  @Test void windowsPrefersLocalAppDataAndSupportsOverride() {
    assertThat(ReiDataDirectory.resolve("Windows 11", Path.of("home"), Map.of("LOCALAPPDATA", "local")))
        .isEqualTo(Path.of("local", "Rei").toAbsolutePath().normalize());
    assertThat(ReiDataDirectory.resolve("Windows 11", Path.of("home"), Map.of("REI_DATA_DIR", "custom")))
        .isEqualTo(Path.of("custom").toAbsolutePath().normalize());
  }
  @Test void unixUsesXdgAndMacUsesApplicationSupport() {
    assertThat(ReiDataDirectory.resolve("Linux", Path.of("home"), Map.of("XDG_DATA_HOME", "xdg")))
        .isEqualTo(Path.of("xdg", "rei").toAbsolutePath().normalize());
    assertThat(ReiDataDirectory.resolve("Mac OS X", Path.of("home"), Map.of()))
        .isEqualTo(Path.of("home", "Library", "Application Support", "Rei").toAbsolutePath().normalize());
  }
}
