package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.config.ExternalConfigFileService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationControllerTest {
  @TempDir Path directory;

  @Test
  void initializationUsesOnlyServerConfiguredPathsAndPreservesExistingFilesWithoutForce()
      throws Exception {
    var service = new ExternalConfigFileService(directory);
    var controller = new ConfigurationController(service);
    assertEquals(service.configFilePath().toString(), controller.path().text());
    assertTrue(
        controller
            .initialize(new ConfigurationController.Initialize(false))
            .text()
            .contains(service.configFilePath().toString()));
    Files.writeString(service.configFilePath(), "custom configuration");
    controller.initialize(new ConfigurationController.Initialize(false));
    assertEquals("custom configuration", Files.readString(service.configFilePath()));
    controller.initialize(new ConfigurationController.Initialize(true));
    assertTrue(Files.readString(service.configFilePath()).contains("spring:"));
  }
}
