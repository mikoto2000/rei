package dev.mikoto2000.rei.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.mikoto2000.rei.launcher.BackendEndpointStore;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** 起動単位の識別子受付窓口の結果と状態の契約を検証するテスト。 */
class InstanceControllerTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証対象に必要な依存先と設定を組み立てるテスト用構成。 */
  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @Import({SecurityConfig.class, InstanceController.class})
  static class Config {
    /** {@code apiKeyProperties} の条件を再現するための補助情報を返す。 */
    @Bean
    ApiKeyProperties apiKeyProperties() {
      return new ApiKeyProperties("test-secret");
    }
  }

  /** Web APIによる操作で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void onlyAuthenticatedClientsSeeReadyInstanceAndNeverCredentials() throws Exception {
    var instance = new BackendInstance(new BackendEndpointStore(root));
    new WebApplicationContextRunner()
        .withPropertyValues("rei.web.enabled=true")
        .withBean(BackendInstance.class, () -> instance)
        .withUserConfiguration(Config.class)
        .run(
            context -> {
              var mvc =
                  MockMvcBuilders.webAppContextSetup(context)
                      .addFilters(
                          context.getBean(
                              "springSecurityFilterChain", jakarta.servlet.Filter.class))
                      .build();
              mvc.perform(get("/api/v1/instance")).andExpect(status().isUnauthorized());
              mvc.perform(get("/api/v1/instance").header("Authorization", "Bearer wrong"))
                  .andExpect(status().isUnauthorized());
              mvc.perform(get("/api/v1/instance").header("Authorization", "Bearer test-secret"))
                  .andExpect(status().isServiceUnavailable());
              instance.ready(8123);
              mvc.perform(get("/api/v1/instance").header("Authorization", "Bearer test-secret"))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.instanceId").value(instance.get().instanceId()))
                  .andExpect(jsonPath("$.storageId").value(instance.get().storageId()))
                  .andExpect(jsonPath("$.apiKey").doesNotExist())
                  .andExpect(
                      content()
                          .string(
                              org.hamcrest.Matchers.not(
                                  org.hamcrest.Matchers.containsString("test-secret"))));
            });
  }
}
