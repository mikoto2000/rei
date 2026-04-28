package dev.mikoto2000.rei.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalConfigFileServiceTest {

  @TempDir
  Path tempDir;

  @Test
  void configFilePathUsesExternalApplicationYamlLocation() {
    ExternalConfigFileService service = new ExternalConfigFileService(tempDir);

    assertEquals(tempDir.resolve(".rei").resolve("application.yaml"), service.configFilePath());
  }

  @Test
  void initializeCreatesTemplateWhenFileDoesNotExist() throws Exception {
    ExternalConfigFileService service = new ExternalConfigFileService(tempDir);

    Path created = service.initializeConfigFile(false);

    assertEquals(service.configFilePath(), created);
    assertTrue(Files.exists(created));
    String content = Files.readString(created);
    assertTrue(content.contains("spring:"));
    assertTrue(content.contains("rei:"));
    assertTrue(content.contains("REI_OPENAI_BASE_URL"));
    assertTrue(content.contains("feed:"));
    assertTrue(content.contains("REI_FEED_CRON"));
    assertTrue(content.contains("0 0 4 * * *"));
    assertTrue(content.contains("small-talk:"));
    assertTrue(content.contains("REI_SMALL_TALK_CRON"));
    assertTrue(content.contains("0 0 12 * * *"));
  }

  @Test
  void initializeDoesNotOverwriteExistingFileWithoutForce() throws Exception {
    ExternalConfigFileService service = new ExternalConfigFileService(tempDir);
    Path configFile = service.configFilePath();
    Files.createDirectories(configFile.getParent());
    Files.writeString(configFile, "custom: true\n");

    Path created = service.initializeConfigFile(false);

    assertEquals(configFile, created);
    assertEquals("custom: true\n", Files.readString(configFile));
  }

  @Test
  void initializeOverwritesExistingFileWhenForceIsTrue() throws Exception {
    ExternalConfigFileService service = new ExternalConfigFileService(tempDir);
    Path configFile = service.configFilePath();
    Files.createDirectories(configFile.getParent());
    Files.writeString(configFile, "custom: true\n");

    service.initializeConfigFile(true);

    String content = Files.readString(configFile);
    assertTrue(content.contains("spring:"));
    assertTrue(content.contains("rei:"));
  }
}
