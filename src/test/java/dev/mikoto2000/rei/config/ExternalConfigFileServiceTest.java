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
    assertTrue(content.contains("skills:"));
    assertTrue(content.contains("${user.dir}/.rei/skills"));
    assertTrue(content.contains("max-selected: 3"));
    assertTrue(content.contains("REI_OPENAI_BASE_URL"));
    assertTrue(content.contains("llm:"));
    assertTrue(content.contains("REI_LLM_MAX_OUTPUT_TOKENS"));
    assertTrue(content.contains("max-output-tokens: ${REI_LLM_MAX_OUTPUT_TOKENS:8192}"));
    assertTrue(content.contains("REI_LLM_OUTPUT_LIMIT_MAX_REPLANS_PER_GOAL"));
    assertTrue(content.contains("REI_LLM_OUTPUT_LIMIT_MAX_SUBGOALS_PER_REPLAN"));
    assertTrue(content.contains("REI_LLM_OUTPUT_LIMIT_MAX_LLM_CALLS_PER_RUN"));
    assertTrue(content.contains("REI_LLM_CHAT_BASE_URL"));
    assertTrue(content.contains("REI_LLM_SEARCH_BASE_URL"));
    assertTrue(content.contains("REI_LLM_BLUESKY_REPLY_BASE_URL"));
    assertTrue(content.contains("REI_LLM_AGENT_SKILLS_BASE_URL"));
    assertTrue(content.contains("REI_LLM_OUTPUT_LIMIT_PLANNER_BASE_URL"));
    assertTrue(content.contains("REI_LLM_IMAGE_GENERATION_BASE_URL"));
    assertTrue(content.contains("REI_LLM_IMAGE_PROMPT_BASE_URL"));
    assertTrue(content.contains("image:"));
    assertTrue(content.contains("REI_IMAGE_OUTPUT_DIRECTORY"));
    assertTrue(content.contains("REI_IMAGE_SIZE"));
    assertTrue(content.contains("REI_IMAGE_RESPONSE_FORMAT"));
    assertTrue(content.contains("REI_IMAGE_TIMEOUT_SECONDS"));
    assertTrue(content.contains("REI_IMAGE_PROMPT_ENHANCEMENT_ENABLED"));
    assertTrue(content.contains("feed:"));
    assertTrue(content.contains("REI_FEED_CRON"));
    assertTrue(content.contains("0 0 4 * * *"));
    assertTrue(content.contains("notification-enabled: false"));
    assertTrue(content.contains("REI_INTEREST_NOTIFICATION_CRON"));
    assertTrue(content.contains("0 0 12 * * *"));
    assertTrue(content.contains("bluesky:"));
    assertTrue(content.contains("REI_BLUESKY_TIMEOUT_SECONDS"));
    assertTrue(content.contains("reply:"));
    assertTrue(content.contains("REI_BLUESKY_REPLY_CHECK_INTERVAL_SECONDS"));
    assertTrue(content.contains("REI_BLUESKY_REPLY_GENERATION_TIMEOUT_SECONDS"));
    assertTrue(content.contains("alice.bsky.social"));
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
