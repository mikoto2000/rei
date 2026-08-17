package dev.mikoto2000.rei.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import dev.mikoto2000.rei.config.ExternalConfigFileService;

class SystemPromptServiceTest {

  @TempDir
  Path tempDir;

  @Test
  void returnsBasePromptWhenAdditionalFileDoesNotExist() {
    SystemPromptService service = newService("base prompt");

    assertEquals("base prompt", service.systemPrompt());
  }

  @Test
  void appendsExternalAdditionalSystemPrompt() throws Exception {
    ExternalConfigFileService externalConfigFileService = new ExternalConfigFileService(tempDir);
    Files.createDirectories(externalConfigFileService.additionalSystemPromptFilePath().getParent());
    Files.writeString(externalConfigFileService.additionalSystemPromptFilePath(), "extra line 1\nextra line 2\n");
    SystemPromptService service = new SystemPromptService(
        new CoreProperties("base prompt", 100),
        new DefaultResourceLoader(),
        externalConfigFileService);

    assertEquals("base prompt" + System.lineSeparator() + System.lineSeparator()
        + "extra line 1\nextra line 2", service.systemPrompt());
  }

  @Test
  void readsBasePromptFromResourceLocation() throws Exception {
    Path basePrompt = tempDir.resolve("base.md");
    Files.writeString(basePrompt, "base from file\n");

    SystemPromptService service = newService(basePrompt.toUri().toString());

    assertEquals("base from file\n", service.systemPrompt());
  }

  @Test
  void usesMarkdownFileContentAsAdditionalPrompt() throws Exception {
    ExternalConfigFileService externalConfigFileService = new ExternalConfigFileService(tempDir);
    Files.createDirectories(externalConfigFileService.additionalSystemPromptFilePath().getParent());
    Files.writeString(externalConfigFileService.additionalSystemPromptFilePath(), "## 追加指示\nplain prompt\n");
    SystemPromptService service = new SystemPromptService(
        new CoreProperties("", 100),
        new DefaultResourceLoader(),
        externalConfigFileService);

    assertEquals("## 追加指示\nplain prompt", service.systemPrompt());
  }

  private SystemPromptService newService(String basePrompt) {
    return new SystemPromptService(
        new CoreProperties(basePrompt, 100),
        new DefaultResourceLoader(),
        new ExternalConfigFileService(tempDir));
  }
}
