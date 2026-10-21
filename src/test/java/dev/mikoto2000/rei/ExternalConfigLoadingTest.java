package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/** 外部設定Loadingの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ExternalConfigLoadingTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** れいの起動で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void externalApplicationYamlOverridesClasspathDefaults() throws Exception {
    Path configFile = tempDir.resolve("application.yaml");
    Files.createDirectories(configFile.getParent());
    Files.writeString(
        configFile,
        """
        rei:
          web-search:
            enabled: false
        """);

    SpringApplication application = new SpringApplication(TestConfiguration.class);
    application.setDefaultProperties(ExternalConfigSupport.defaultProperties(tempDir));

    try (ConfigurableApplicationContext context =
        application.run(
            "--spring.main.web-application-type=none",
            "--rei.data-dir=" + tempDir,
            "--logging.file.name=target/test-external-config.log")) {
      assertEquals("false", context.getEnvironment().getProperty("rei.web-search.enabled"));
    }
  }

  /** 検証対象に必要な依存先と設定を組み立てるテスト用構成。 */
  @Configuration
  static class TestConfiguration {}
}
