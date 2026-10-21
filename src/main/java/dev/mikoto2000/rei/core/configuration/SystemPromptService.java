package dev.mikoto2000.rei.core.configuration;

import dev.mikoto2000.rei.config.ExternalConfigFileService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/** システムへの指示のユースケースを実現する。 */
@Service
public class SystemPromptService {

  /** core設定。 */
  private final CoreProperties coreProperties;

  /** 補助資料読み込みの窓口。 */
  private final ResourceLoader resourceLoader;

  /** 外部設定ファイルサービス。 */
  private final ExternalConfigFileService externalConfigFileService;

  /** 指定された値や利用先を使って、SystemPromptServiceを初期化する。 */
  @Autowired
  public SystemPromptService(
      CoreProperties coreProperties,
      ResourceLoader resourceLoader,
      ExternalConfigFileService externalConfigFileService) {
    this.coreProperties = coreProperties;
    this.resourceLoader = resourceLoader;
    this.externalConfigFileService = externalConfigFileService;
  }

  /** 機能の組み立てで用いるシステムへの指示を返す。 */
  public String systemPrompt() {
    String base = readBaseSystemPrompt();
    String additional = readAdditionalSystemPrompt();
    if (additional.isBlank()) {
      return base;
    }
    if (base.isBlank()) {
      return additional.strip();
    }
    return base.stripTrailing()
        + System.lineSeparator()
        + System.lineSeparator()
        + additional.strip();
  }

  /** 必要な内容を参照できるよう、基準システムへの指示を読み取る。 */
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

  /** 必要な内容を参照できるよう、追加システムへの指示を読み取る。 */
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
