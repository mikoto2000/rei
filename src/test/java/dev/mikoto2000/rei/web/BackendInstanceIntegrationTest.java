package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.launcher.BackendConnectionProbe;
import dev.mikoto2000.rei.launcher.BackendEndpointStore;
import dev.mikoto2000.rei.launcher.BackendOwnership;
import java.net.http.HttpClient;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** バックエンド起動単位の識別子外部連携の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class BackendInstanceIntegrationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証対象に必要な依存先と設定を組み立てるテスト用構成。 */
  @Configuration(proxyBeanMethods = false)
  @Import({BackendInstanceConfiguration.class, InstanceController.class, SecurityConfig.class})
  @ImportAutoConfiguration({
    org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
        .class,
    org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.class,
    org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration.class,
    org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class
  })
  static class Config {
    /** {@code storageStartupGate} の条件を再現するための補助情報を返す。 */
    @Bean
    static dev.mikoto2000.rei.storage.StorageMigrationConfiguration.StartupGate
        storageStartupGate() {
      return new dev.mikoto2000.rei.storage.StorageMigrationConfiguration.StartupGate();
    }
  }

  /** 実際のHTTP通信準備完了識別情報について、再開の後も対象の情報が残ることを確認する。 */
  @Test
  void actualHttpReadyIdentitySurvivesRestartAndLeaseAndEndpointCloseTogether() throws Exception {
    var store = new BackendEndpointStore(root);
    String storageId = null, previousInstance = null;
    for (int restart = 0; restart < 2; restart++) {
      var app = new SpringApplication(Config.class);
      WebApplication.configure(app, "integration-secret");
      try (var context =
              app.run(
                  "--rei.data-dir=" + root,
                  "--rei.web.port=0",
                  "--logging.config=classpath:web-test-logback.xml");
          var client = HttpClient.newHttpClient()) {
        var endpoint = store.read().orElseThrow();
        assertTrue(BackendOwnership.isOwned(root));
        assertEquals(
            BackendConnectionProbe.Status.READY,
            new BackendConnectionProbe(client)
                .check(endpoint, "integration-secret", true)
                .status());
        assertEquals(
            BackendConnectionProbe.Status.AUTHENTICATION_FAILED,
            new BackendConnectionProbe(client).check(endpoint, "wrong", true).status());
        if (storageId == null) storageId = endpoint.storageId();
        else assertEquals(storageId, endpoint.storageId());
        assertNotEquals(previousInstance, endpoint.instanceId());
        previousInstance = endpoint.instanceId();
      }
      assertTrue(store.read().isEmpty());
      assertFalse(BackendOwnership.isOwned(root));
    }
  }
}
