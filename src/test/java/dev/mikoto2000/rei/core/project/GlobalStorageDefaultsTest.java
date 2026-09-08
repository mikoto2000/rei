package dev.mikoto2000.rei.core.project;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GlobalStorageDefaultsTest {
  @Test void bundledDefaultsDoNotCreateProjectLocalReiDirectory() throws Exception {
    assertThat(new dev.mikoto2000.rei.config.ExternalConfigFileService().configFilePath())
        .isEqualTo(dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current().resolve("application.yaml"));
    assertThat(Files.readString(Path.of("src/main/resources/application.yaml"))).doesNotContain("/.rei", "file:.rei", ":.rei");
    assertThat(Files.readString(Path.of("src/main/resources/logback-spring.xml"))).doesNotContain("./.rei");
    assertThat(new dev.mikoto2000.rei.image.ImageProperties().getOutputDirectory().toString()).doesNotContain(".rei");
    assertThat(new dev.mikoto2000.rei.core.configuration.SqliteVecProperties().getCacheDir()).doesNotContain(".rei");
  }
}
