package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.config.ExternalConfigFileService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 機能の構成受付窓口の結果と状態の契約を検証するテスト。 */
class ConfigurationControllerTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 初期化利用する限定サーバー設定済みパス一覧およびについて、既存のファイル一覧を保持することを確認する。 */
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
