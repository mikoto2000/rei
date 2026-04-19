package dev.mikoto2000.rei;

import java.nio.file.Path;
import java.util.Map;

import dev.mikoto2000.rei.core.datasource.ReiPaths;

final class ExternalConfigSupport {

  private ExternalConfigSupport() {
  }

  static Map<String, Object> defaultProperties() {
    return defaultProperties(Path.of("").toAbsolutePath().normalize());
  }

  static Map<String, Object> defaultProperties(Path workDirectory) {
    return Map.of("spring.config.additional-location", additionalLocation(workDirectory));
  }

  static String additionalLocation(Path workDirectory) {
    return "optional:file:" + ReiPaths.configFilePath(workDirectory);
  }
}
