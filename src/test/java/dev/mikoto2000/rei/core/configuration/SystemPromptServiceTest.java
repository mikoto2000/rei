package dev.mikoto2000.rei.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mikoto2000.rei.config.ExternalConfigFileService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

/** システムへの指示サービスの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class SystemPromptServiceTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** 基準プロンプトを返すことを確認する。 */
  @Test
  void returnsBasePromptWhenAdditionalFileDoesNotExist() {
    SystemPromptService service = newService("base prompt");

    assertEquals("base prompt", service.systemPrompt());
  }

  /** 機能の組み立てで、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void appendsExternalAdditionalSystemPrompt() throws Exception {
    ExternalConfigFileService externalConfigFileService = new ExternalConfigFileService(tempDir);
    Files.createDirectories(externalConfigFileService.additionalSystemPromptFilePath().getParent());
    Files.writeString(
        externalConfigFileService.additionalSystemPromptFilePath(), "extra line 1\nextra line 2\n");
    SystemPromptService service =
        new SystemPromptService(
            new CoreProperties("base prompt", 100),
            new DefaultResourceLoader(),
            externalConfigFileService);

    assertEquals(
        "base prompt"
            + System.lineSeparator()
            + System.lineSeparator()
            + "extra line 1\nextra line 2",
        service.systemPrompt());
  }

  /** 基準プロンプトを読み取ることを確認する。 */
  @Test
  void readsBasePromptFromResourceLocation() throws Exception {
    Path basePrompt = tempDir.resolve("base.md");
    Files.writeString(basePrompt, "base from file\n");

    SystemPromptService service = newService(basePrompt.toUri().toString());

    assertEquals("base from file\n", service.systemPrompt());
  }

  /** Markdownファイル内容形式追加プロンプトを利用することを確認する。 */
  @Test
  void usesMarkdownFileContentAsAdditionalPrompt() throws Exception {
    ExternalConfigFileService externalConfigFileService = new ExternalConfigFileService(tempDir);
    Files.createDirectories(externalConfigFileService.additionalSystemPromptFilePath().getParent());
    Files.writeString(
        externalConfigFileService.additionalSystemPromptFilePath(), "## 追加指示\nplain prompt\n");
    SystemPromptService service =
        new SystemPromptService(
            new CoreProperties("", 100), new DefaultResourceLoader(), externalConfigFileService);

    assertEquals("## 追加指示\nplain prompt", service.systemPrompt());
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private SystemPromptService newService(String basePrompt) {
    return new SystemPromptService(
        new CoreProperties(basePrompt, 100),
        new DefaultResourceLoader(),
        new ExternalConfigFileService(tempDir));
  }
}
