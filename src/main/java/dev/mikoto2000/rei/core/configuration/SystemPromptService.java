package dev.mikoto2000.rei.core.configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import dev.mikoto2000.rei.config.ExternalConfigFileService;

@Service
public class SystemPromptService {

  private final CoreProperties coreProperties;
  private final ResourceLoader resourceLoader;
  private final ExternalConfigFileService externalConfigFileService;

  @Autowired
  public SystemPromptService(CoreProperties coreProperties, ResourceLoader resourceLoader,
      ExternalConfigFileService externalConfigFileService) {
    this.coreProperties = coreProperties;
    this.resourceLoader = resourceLoader;
    this.externalConfigFileService = externalConfigFileService;
  }

  public String systemPrompt() {
    String base = readBaseSystemPrompt();
    String additional = readAdditionalSystemPrompt();
    if (additional.isBlank()) {
      return base;
    }
    if (base.isBlank()) {
      return additional.strip();
    }
    return base.stripTrailing() + System.lineSeparator() + System.lineSeparator() + additional.strip();
  }

  private String readBaseSystemPrompt() {
    String configured = coreProperties.systemPrompt();
    if (configured == null || configured.isBlank()) {
      return "";
    }
    Resource resource = resourceLoader.getResource(configured);
    if (!resource.exists()) {
      return configured;
    }
    try {
      return resource.getContentAsString(StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("system prompt の読み込みに失敗しました: " + configured, e);
    }
  }

  private String readAdditionalSystemPrompt() {
    Path file = externalConfigFileService.additionalSystemPromptFilePath();
    if (!Files.exists(file)) {
      return "";
    }
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("追加 system prompt の読み込みに失敗しました: " + file, e);
    }
  }
}
